# flutter_ijkplayer

A new Flutter project.

## Getting Started

add code below in your /android/app/build.gradle
```gradle
android{
    repositories {
        flatDir {
            dirs project(':flutter_ijkplayer').file('libs'), 'libs'
        }
    }
}
```
