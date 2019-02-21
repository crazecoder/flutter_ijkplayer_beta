/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.offline;

import static com.google.android.exoplayer2.offline.DownloadState.FAILURE_REASON_NONE;
import static com.google.android.exoplayer2.offline.DownloadState.FAILURE_REASON_UNKNOWN;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_COMPLETED;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_DOWNLOADING;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_FAILED;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_QUEUED;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_REMOVED;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_REMOVING;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_RESTARTING;
import static com.google.android.exoplayer2.offline.DownloadState.STATE_STOPPED;
import static com.google.android.exoplayer2.offline.DownloadState.STOP_FLAG_MANUAL;
import static com.google.android.exoplayer2.offline.DownloadState.STOP_FLAG_REQUIREMENTS_NOT_MET;

import android.content.Context;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.scheduler.Requirements;
import com.google.android.exoplayer2.scheduler.RequirementsWatcher;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Log;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Manages multiple stream download and remove requests.
 *
 * <p>A download manager instance must be accessed only from the thread that created it, unless that
 * thread does not have a {@link Looper}. In that case, it must be accessed only from the
 * application's main thread. Registered listeners will be called on the same thread.
 */
public final class DownloadManager {

  /** Listener for {@link DownloadManager} events. */
  public interface Listener {
    /**
     * Called when all actions have been restored.
     *
     * @param downloadManager The reporting instance.
     */
    void onInitialized(DownloadManager downloadManager);
    /**
     * Called when the state of a download changes.
     *
     * @param downloadManager The reporting instance.
     * @param downloadState The state of the download.
     */
    void onDownloadStateChanged(DownloadManager downloadManager, DownloadState downloadState);

    /**
     * Called when there is no active download left.
     *
     * @param downloadManager The reporting instance.
     */
    void onIdle(DownloadManager downloadManager);

    /**
     * Called when the download requirements state changed.
     *
     * @param downloadManager The reporting instance.
     * @param requirements Requirements needed to be met to start downloads.
     * @param notMetRequirements {@link Requirements.RequirementFlags RequirementFlags} that are not
     *     met, or 0.
     */
    void onRequirementsStateChanged(
        DownloadManager downloadManager,
        Requirements requirements,
        @Requirements.RequirementFlags int notMetRequirements);
  }

  /** The default maximum number of simultaneous downloads. */
  public static final int DEFAULT_MAX_SIMULTANEOUS_DOWNLOADS = 1;
  /** The default minimum number of times a download must be retried before failing. */
  public static final int DEFAULT_MIN_RETRY_COUNT = 5;
  /** The default requirement is that the device has network connectivity. */
  public static final Requirements DEFAULT_REQUIREMENTS =
      new Requirements(Requirements.NETWORK_TYPE_ANY, false, false);

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    START_THREAD_SUCCEEDED,
    START_THREAD_WAIT_REMOVAL_TO_FINISH,
    START_THREAD_WAIT_DOWNLOAD_CANCELLATION,
    START_THREAD_TOO_MANY_DOWNLOADS,
    START_THREAD_NOT_ALLOWED
  })
  private @interface StartThreadResults {}

  private static final int START_THREAD_SUCCEEDED = 0;
  private static final int START_THREAD_WAIT_REMOVAL_TO_FINISH = 1;
  private static final int START_THREAD_WAIT_DOWNLOAD_CANCELLATION = 2;
  private static final int START_THREAD_TOO_MANY_DOWNLOADS = 3;
  private static final int START_THREAD_NOT_ALLOWED = 4;

  private static final String TAG = "DownloadManager";
  private static final boolean DEBUG = false;

  private final int maxSimultaneousDownloads;
  private final int minRetryCount;
  private final Context context;
  private final ActionFile actionFile;
  private final DownloaderFactory downloaderFactory;
  private final ArrayList<Download> downloads;
  private final HashMap<Download, DownloadThread> activeDownloads;
  private final Handler handler;
  private final HandlerThread fileIOThread;
  private final Handler fileIOHandler;
  private final CopyOnWriteArraySet<Listener> listeners;
  private final ArrayDeque<DownloadAction> actionQueue;

  private boolean initialized;
  private boolean released;
  @DownloadState.StopFlags private int stopFlags;
  @Requirements.RequirementFlags private int notMetRequirements;
  private int manualStopReason;
  private RequirementsWatcher requirementsWatcher;
  private int simultaneousDownloads;

  /**
   * Constructs a {@link DownloadManager}.
   *
   * @param context Any context.
   * @param actionFile The file in which active actions are saved.
   * @param downloaderFactory A factory for creating {@link Downloader}s.
   */
  public DownloadManager(Context context, File actionFile, DownloaderFactory downloaderFactory) {
    this(
        context,
        actionFile,
        downloaderFactory,
        DEFAULT_MAX_SIMULTANEOUS_DOWNLOADS,
        DEFAULT_MIN_RETRY_COUNT,
        DEFAULT_REQUIREMENTS);
  }

  /**
   * Constructs a {@link DownloadManager}.
   *
   * @param context Any context.
   * @param actionFile The file in which active actions are saved.
   * @param downloaderFactory A factory for creating {@link Downloader}s.
   * @param maxSimultaneousDownloads The maximum number of simultaneous downloads.
   * @param minRetryCount The minimum number of times a download must be retried before failing.
   * @param requirements The requirements needed to be met to start downloads.
   */
  public DownloadManager(
      Context context,
      File actionFile,
      DownloaderFactory downloaderFactory,
      int maxSimultaneousDownloads,
      int minRetryCount,
      Requirements requirements) {
    this.context = context.getApplicationContext();
    this.actionFile = new ActionFile(actionFile);
    this.downloaderFactory = downloaderFactory;
    this.maxSimultaneousDownloads = maxSimultaneousDownloads;
    this.minRetryCount = minRetryCount;

    stopFlags = STOP_FLAG_MANUAL;
    downloads = new ArrayList<>();
    activeDownloads = new HashMap<>();

    Looper looper = Looper.myLooper();
    if (looper == null) {
      looper = Looper.getMainLooper();
    }
    handler = new Handler(looper);

    fileIOThread = new HandlerThread("DownloadManager file i/o");
    fileIOThread.start();
    fileIOHandler = new Handler(fileIOThread.getLooper());

    listeners = new CopyOnWriteArraySet<>();
    actionQueue = new ArrayDeque<>();

    setNotMetRequirements(watchRequirements(requirements));
    loadActions();
    logd("Created");
  }

  /**
   * Sets the requirements needed to be met to start downloads.
   *
   * @param requirements Need to be met to start downloads.
   */
  public void setRequirements(Requirements requirements) {
    Assertions.checkState(!released);
    if (requirements.equals(requirementsWatcher.getRequirements())) {
      return;
    }
    requirementsWatcher.stop();
    onRequirementsStateChanged(watchRequirements(requirements));
  }

  /** Returns the requirements needed to be met to start downloads. */
  public Requirements getRequirements() {
    return requirementsWatcher.getRequirements();
  }

  /**
   * Adds a {@link Listener}.
   *
   * @param listener The listener to be added.
   */
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a {@link Listener}.
   *
   * @param listener The listener to be removed.
   */
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  /**
   * Clears {@link DownloadState#STOP_FLAG_MANUAL} flag of all downloads. Downloads are started if
   * the requirements are met.
   */
  public void startDownloads() {
    logd("manual stopped is cancelled");
    manualStopReason = 0;
    stopFlags &= ~STOP_FLAG_MANUAL;
    for (int i = 0; i < downloads.size(); i++) {
      downloads.get(i).clearManualStopReason();
    }
  }

  /** Signals all downloads to stop. Call {@link #startDownloads()} to let them to be started. */
  public void stopDownloads() {
    stopDownloads(/* manualStopReason= */ 0);
  }

  /**
   * Signals all downloads to stop. Call {@link #startDownloads()} to let them to be started.
   *
   * @param manualStopReason An application defined stop reason.
   */
  public void stopDownloads(int manualStopReason) {
    logd("downloads are stopped manually");
    this.manualStopReason = manualStopReason;
    stopFlags |= STOP_FLAG_MANUAL;
    for (int i = 0; i < downloads.size(); i++) {
      downloads.get(i).setManualStopReason(this.manualStopReason);
    }
  }

  /**
   * Clears {@link DownloadState#STOP_FLAG_MANUAL} flag of the download with the {@code id}.
   * Download is started if the requirements are met.
   *
   * @param id The unique content id of the download to be started.
   */
  public void startDownload(String id) {
    Download download = getDownload(id);
    if (download != null) {
      logd("download is started manually", download);
      download.clearManualStopReason();
    }
  }

  /**
   * Signals the download with the {@code id} to stop. Call {@link #startDownload(String)} to let it
   * to be started.
   *
   * @param id The unique content id of the download to be stopped.
   */
  public void stopDownload(String id) {
    stopDownload(id, /* manualStopReason= */ 0);
  }

  /**
   * Signals the download with the {@code id} to stop. Call {@link #startDownload(String)} to let it
   * to be started.
   *
   * @param id The unique content id of the download to be stopped.
   * @param manualStopReason An application defined stop reason.
   */
  public void stopDownload(String id, int manualStopReason) {
    Download download = getDownload(id);
    if (download != null) {
      logd("download is stopped manually", download);
      download.setManualStopReason(manualStopReason);
    }
  }

  /**
   * Handles the given action.
   *
   * @param action The action to be executed.
   */
  public void handleAction(DownloadAction action) {
    Assertions.checkState(!released);
    if (initialized) {
      addDownloadForAction(action);
      saveActions();
    } else {
      actionQueue.add(action);
    }
  }

  /** Returns the number of downloads. */
  public int getDownloadCount() {
    Assertions.checkState(!released);
    return downloads.size();
  }

  /**
   * Returns {@link DownloadState} for the given content id, or null if no such download exists.
   *
   * @param id The unique content id.
   * @return DownloadState for the given content id, or null if no such download exists.
   */
  @Nullable
  public DownloadState getDownloadState(String id) {
    Assertions.checkState(!released);
    Download download = getDownload(id);
    return download != null ? download.getDownloadState() : null;
  }

  /** Returns the states of all current downloads. */
  public DownloadState[] getAllDownloadStates() {
    Assertions.checkState(!released);
    DownloadState[] states = new DownloadState[downloads.size()];
    for (int i = 0; i < states.length; i++) {
      states[i] = downloads.get(i).getDownloadState();
    }
    return states;
  }

  /** Returns whether the manager has completed initialization. */
  public boolean isInitialized() {
    Assertions.checkState(!released);
    return initialized;
  }

  /** Returns whether there are no active downloads. */
  public boolean isIdle() {
    Assertions.checkState(!released);
    return initialized && activeDownloads.isEmpty();
  }

  /**
   * Stops all of the downloads and releases resources. If the action file isn't up to date, waits
   * for the changes to be written. The manager must not be accessed after this method has been
   * called.
   */
  public void release() {
    if (released) {
      return;
    }
    released = true;
    stopAllDownloadThreads();
    if (requirementsWatcher != null) {
      requirementsWatcher.stop();
    }
    final ConditionVariable fileIOFinishedCondition = new ConditionVariable();
    fileIOHandler.post(fileIOFinishedCondition::open);
    fileIOFinishedCondition.block();
    fileIOThread.quit();
    logd("Released");
  }

  private void addDownloadForAction(DownloadAction action) {
    for (int i = 0; i < downloads.size(); i++) {
      Download download = downloads.get(i);
      if (download.addAction(action)) {
        logd("Action is added to existing download", download);
        return;
      }
    }
    Download download = new Download(this, action, stopFlags, notMetRequirements, manualStopReason);
    downloads.add(download);
    logd("Download is added", download);
  }

  private void maybeNotifyListenersIdle() {
    if (!isIdle()) {
      return;
    }
    logd("Notify idle state");
    for (Listener listener : listeners) {
      listener.onIdle(this);
    }
  }

  private void onDownloadStateChange(Download download) {
    if (released) {
      return;
    }
    logd("Download state is changed", download);
    DownloadState downloadState = download.getDownloadState();
    for (Listener listener : listeners) {
      listener.onDownloadStateChanged(this, downloadState);
    }
    if (download.isFinished()) {
      downloads.remove(download);
      saveActions();
    }
  }

  private void onRequirementsStateChanged(@Requirements.RequirementFlags int notMetRequirements) {
    setNotMetRequirements(notMetRequirements);
    logdFlags("Not met requirements are changed", notMetRequirements);
    Requirements requirements = requirementsWatcher.getRequirements();
    for (Listener listener : listeners) {
      listener.onRequirementsStateChanged(DownloadManager.this, requirements, notMetRequirements);
    }
    for (int i = 0; i < downloads.size(); i++) {
      downloads.get(i).setNotMetRequirements(notMetRequirements);
    }
  }

  private void setNotMetRequirements(@Requirements.RequirementFlags int notMetRequirements) {
    this.notMetRequirements = notMetRequirements;
    if (notMetRequirements == 0) {
      stopFlags &= ~STOP_FLAG_REQUIREMENTS_NOT_MET;
    } else {
      stopFlags |= STOP_FLAG_REQUIREMENTS_NOT_MET;
    }
  }

  @Nullable
  private Download getDownload(String id) {
    for (int i = 0; i < downloads.size(); i++) {
      Download download = downloads.get(i);
      if (download.getId().equals(id)) {
        return download;
      }
    }
    return null;
  }

  private void loadActions() {
    fileIOHandler.post(
        () -> {
          DownloadAction[] loadedActions;
          try {
            loadedActions = actionFile.load();
            logd("Action file is loaded.");
          } catch (Throwable e) {
            Log.e(TAG, "Action file loading failed.", e);
            loadedActions = new DownloadAction[0];
          }
          final DownloadAction[] actions = loadedActions;
          handler.post(
              () -> {
                if (released) {
                  return;
                }
                for (DownloadAction action : actions) {
                  addDownloadForAction(action);
                }
                if (!actionQueue.isEmpty()) {
                  while (!actionQueue.isEmpty()) {
                    addDownloadForAction(actionQueue.remove());
                  }
                  saveActions();
                }
                logd("Downloads are created.");
                initialized = true;
                for (Listener listener : listeners) {
                  listener.onInitialized(DownloadManager.this);
                }
                for (int i = 0; i < downloads.size(); i++) {
                  downloads.get(i).start();
                }
              });
        });
  }

  private void saveActions() {
    if (released) {
      return;
    }
    ArrayList<DownloadAction> actions = new ArrayList<>(downloads.size());
    for (int i = 0; i < downloads.size(); i++) {
      downloads.get(i).addActions(actions);
    }
    final DownloadAction[] actionsArray = actions.toArray(new DownloadAction[0]);
    fileIOHandler.post(
        () -> {
          try {
            actionFile.store(actionsArray);
            logd("Actions persisted.");
          } catch (IOException e) {
            Log.e(TAG, "Persisting actions failed.", e);
          }
        });
  }

  private static void logd(String message) {
    if (DEBUG) {
      Log.d(TAG, message);
    }
  }

  private static void logd(String message, Download download) {
    if (DEBUG) {
      logd(message + ": " + download);
    }
  }

  private static void logdFlags(String message, int flags) {
    if (DEBUG) {
      logd(message + ": " + Integer.toBinaryString(flags));
    }
  }

  @Requirements.RequirementFlags
  private int watchRequirements(Requirements requirements) {
    RequirementsWatcher.Listener listener =
        (requirementsWatcher, notMetRequirements) -> onRequirementsStateChanged(notMetRequirements);
    requirementsWatcher = new RequirementsWatcher(context, listener, requirements);
    @Requirements.RequirementFlags int notMetRequirements = requirementsWatcher.start();
    if (notMetRequirements == 0) {
      startDownloads();
    } else {
      stopDownloads();
    }
    return notMetRequirements;
  }

  @StartThreadResults
  private int startDownloadThread(Download download, DownloadAction action) {
    if (!initialized || released) {
      return START_THREAD_NOT_ALLOWED;
    }
    if (activeDownloads.containsKey(download)) {
      if (stopDownloadThread(download)) {
        return START_THREAD_WAIT_DOWNLOAD_CANCELLATION;
      }
      return START_THREAD_WAIT_REMOVAL_TO_FINISH;
    }
    if (!action.isRemoveAction) {
      if (simultaneousDownloads == maxSimultaneousDownloads) {
        return START_THREAD_TOO_MANY_DOWNLOADS;
      }
      simultaneousDownloads++;
    }
    Downloader downloader = downloaderFactory.createDownloader(action);
    DownloadThread downloadThread = new DownloadThread(download, downloader, action.isRemoveAction);
    activeDownloads.put(download, downloadThread);
    logd("Download is started", download);
    return START_THREAD_SUCCEEDED;
  }

  private boolean stopDownloadThread(Download download) {
    DownloadThread downloadThread = activeDownloads.get(download);
    if (downloadThread != null && !downloadThread.isRemoveThread) {
      downloadThread.cancel();
      logd("Download is cancelled", download);
      return true;
    }
    return false;
  }

  private void stopAllDownloadThreads() {
    for (Download download : activeDownloads.keySet()) {
      stopDownloadThread(download);
    }
  }

  private void onDownloadThreadStopped(DownloadThread downloadThread, Throwable finalError) {
    if (released) {
      return;
    }
    Download download = downloadThread.download;
    logd("Download is stopped", download);
    activeDownloads.remove(download);
    boolean tryToStartDownloads = false;
    if (!downloadThread.isRemoveThread) {
      // If maxSimultaneousDownloads was hit, there might be a download waiting for a slot.
      tryToStartDownloads = simultaneousDownloads == maxSimultaneousDownloads;
      simultaneousDownloads--;
    }
    download.onDownloadThreadStopped(downloadThread.isCanceled, finalError);
    if (tryToStartDownloads) {
      for (int i = 0;
          simultaneousDownloads < maxSimultaneousDownloads && i < downloads.size();
          i++) {
        downloads.get(i).start();
      }
    }
    maybeNotifyListenersIdle();
  }

  @Nullable
  private Downloader getDownloader(Download download) {
    DownloadThread downloadThread = activeDownloads.get(download);
    if (downloadThread != null) {
      return downloadThread.downloader;
    }
    return null;
  }

  private static final class Download {
    private final DownloadManager downloadManager;

    private DownloadState downloadState;
    @DownloadState.State private int state;
    @MonotonicNonNull @DownloadState.FailureReason private int failureReason;
    @DownloadState.StopFlags private int stopFlags;
    @Requirements.RequirementFlags private int notMetRequirements;
    private int manualStopReason;

    private Download(
        DownloadManager downloadManager,
        DownloadAction action,
        @DownloadState.StopFlags int stopFlags,
        @Requirements.RequirementFlags int notMetRequirements,
        int manualStopReason) {
      this.downloadManager = downloadManager;
      this.notMetRequirements = notMetRequirements;
      this.manualStopReason = manualStopReason;
      this.stopFlags = stopFlags;
      downloadState = new DownloadState(action);

      initialize(downloadState.state);
    }

    public String getId() {
      return downloadState.id;
    }

    public boolean addAction(DownloadAction newAction) {
      if (!getId().equals(newAction.id)) {
        return false;
      }
      Assertions.checkState(downloadState.type.equals(newAction.type));
      downloadState = downloadState.mergeAction(newAction);
      initialize(downloadState.state);
      return true;
    }

    public DownloadState getDownloadState() {
      float downloadPercentage = C.PERCENTAGE_UNSET;
      long downloadedBytes = 0;
      long totalBytes = C.LENGTH_UNSET;
      Downloader downloader = downloadManager.getDownloader(this);
      if (downloader != null) {
        downloadPercentage = downloader.getDownloadPercentage();
        downloadedBytes = downloader.getDownloadedBytes();
        totalBytes = downloader.getTotalBytes();
      }
      downloadState =
          new DownloadState(
              downloadState.id,
              downloadState.type,
              downloadState.uri,
              downloadState.cacheKey,
              state,
              downloadPercentage,
              downloadedBytes,
              totalBytes,
              state != STATE_FAILED ? FAILURE_REASON_NONE : failureReason,
              stopFlags,
              notMetRequirements,
              manualStopReason,
              downloadState.startTimeMs,
              /* updateTimeMs= */ System.currentTimeMillis(),
              downloadState.streamKeys,
              downloadState.customMetadata);
      return downloadState;
    }

    public boolean isFinished() {
      return state == STATE_FAILED || state == STATE_COMPLETED || state == STATE_REMOVED;
    }

    public boolean isIdle() {
      return state != STATE_DOWNLOADING && state != STATE_REMOVING && state != STATE_RESTARTING;
    }

    @Override
    public String toString() {
      return getId() + ' ' + DownloadState.getStateString(state);
    }

    public void start() {
      if (state == STATE_QUEUED || state == STATE_DOWNLOADING) {
        startOrQueue();
      } else if (state == STATE_REMOVING || state == STATE_RESTARTING) {
        downloadManager.startDownloadThread(this, getAction());
      }
    }

    public void setNotMetRequirements(@Requirements.RequirementFlags int notMetRequirements) {
      this.notMetRequirements = notMetRequirements;
      updateStopFlags(STOP_FLAG_REQUIREMENTS_NOT_MET, /* setFlags= */ notMetRequirements != 0);
    }

    public void setManualStopReason(int manualStopReason) {
      this.manualStopReason = manualStopReason;
      updateStopFlags(STOP_FLAG_MANUAL, /* setFlags= */ true);
    }

    public void clearManualStopReason() {
      this.manualStopReason = 0;
      updateStopFlags(STOP_FLAG_MANUAL, /* setFlags= */ false);
    }

    private void updateStopFlags(int flags, boolean setFlags) {
      if (setFlags) {
        stopFlags |= flags;
      } else {
        stopFlags &= ~flags;
      }
      if (stopFlags != 0) {
        if (state == STATE_DOWNLOADING || state == STATE_QUEUED) {
          downloadManager.stopDownloadThread(this);
          setState(STATE_STOPPED);
        }
      } else if (state == STATE_STOPPED) {
        startOrQueue();
      }
    }

    private void initialize(int initialState) {
      // Don't notify listeners with initial state until we make sure we don't switch to
      // another state immediately.
      state = initialState;
      if (state == STATE_REMOVING || state == STATE_RESTARTING) {
        downloadManager.startDownloadThread(this, getAction());
      } else if (stopFlags != 0) {
        setState(STATE_STOPPED);
      } else {
        startOrQueue();
      }
      if (state == initialState) {
        downloadManager.onDownloadStateChange(this);
      }
    }

    private void startOrQueue() {
      Assertions.checkState(!(state == STATE_REMOVING || state == STATE_RESTARTING));
      @StartThreadResults int result = downloadManager.startDownloadThread(this, getAction());
      Assertions.checkState(result != START_THREAD_WAIT_REMOVAL_TO_FINISH);
      if (result == START_THREAD_SUCCEEDED || result == START_THREAD_WAIT_DOWNLOAD_CANCELLATION) {
        setState(STATE_DOWNLOADING);
      } else {
        setState(STATE_QUEUED);
      }
    }

    private DownloadAction getAction() {
      Assertions.checkState(state != STATE_REMOVED);
      if (state == STATE_REMOVING || state == STATE_RESTARTING) {
        return DownloadAction.createRemoveAction(
            downloadState.type, downloadState.uri, downloadState.cacheKey);
      }
      return getDownloadAction(downloadState);
    }

    private void addActions(List<DownloadAction> actions) {
      actions.add(getAction());
      if (state == STATE_RESTARTING) {
        actions.add(getDownloadAction(downloadState));
      }
    }

    private void setState(@DownloadState.State int newState) {
      if (state != newState) {
        state = newState;
        downloadManager.onDownloadStateChange(this);
      }
    }

    private void onDownloadThreadStopped(boolean isCanceled, @Nullable Throwable error) {
      if (isIdle()) {
        return;
      }
      if (isCanceled) {
        downloadManager.startDownloadThread(this, getAction());
      } else if (state == STATE_RESTARTING) {
        initialize(STATE_QUEUED);
      } else if (state == STATE_REMOVING) {
        setState(STATE_REMOVED);
      } else { // STATE_DOWNLOADING
        if (error != null) {
          failureReason = FAILURE_REASON_UNKNOWN;
          setState(STATE_FAILED);
        } else {
          setState(STATE_COMPLETED);
        }
      }
    }

    private static DownloadAction getDownloadAction(DownloadState downloadState) {
      return DownloadAction.createDownloadAction(
          downloadState.type,
          downloadState.uri,
          Arrays.asList(downloadState.streamKeys),
          downloadState.cacheKey,
          downloadState.customMetadata);
    }
  }

  private class DownloadThread implements Runnable {

    private final Download download;
    private final Downloader downloader;
    private final boolean isRemoveThread;
    private final Thread thread;
    private volatile boolean isCanceled;

    private DownloadThread(Download download, Downloader downloader, boolean isRemoveThread) {
      this.download = download;
      this.downloader = downloader;
      this.isRemoveThread = isRemoveThread;
      thread = new Thread(this);
      thread.start();
    }

    public void cancel() {
      isCanceled = true;
      downloader.cancel();
      thread.interrupt();
    }

    // Methods running on download thread.

    @Override
    public void run() {
      logd("Download started", download);
      Throwable error = null;
      try {
        if (isRemoveThread) {
          downloader.remove();
        } else {
          int errorCount = 0;
          long errorPosition = C.LENGTH_UNSET;
          while (!isCanceled) {
            try {
              downloader.download();
              break;
            } catch (IOException e) {
              if (!isCanceled) {
                long downloadedBytes = downloader.getDownloadedBytes();
                if (downloadedBytes != errorPosition) {
                  logd("Reset error count. downloadedBytes = " + downloadedBytes, download);
                  errorPosition = downloadedBytes;
                  errorCount = 0;
                }
                if (++errorCount > minRetryCount) {
                  throw e;
                }
                logd("Download error. Retry " + errorCount, download);
                Thread.sleep(getRetryDelayMillis(errorCount));
              }
            }
          }
        }
      } catch (Throwable e) {
        error = e;
      }
      final Throwable finalError = error;
      handler.post(() -> onDownloadThreadStopped(this, finalError));
    }

    private int getRetryDelayMillis(int errorCount) {
      return Math.min((errorCount - 1) * 1000, 5000);
    }
  }

}