# ASLRecorder (WIP) 

Android app for Deaf and hard-of-hearing (DHH) users to record training videos for
sign language recognition models. Forked from Sahir Shahryar's ASLRecorder: https://github.com/sahirshahryar/ASLRecorder


## Progress
- [x] Show camera feed
- [x] Save video recordings
- [x] Use round button instead of rectangular one
- [x] Show which word the user should sign at the top of the interface
  - [x] Automatically label recordings by the word shown at the top of the UI
- [x] Actual list of words!
- [x] Dynamic device compatibility (query device capabilities instead of assuming them)
  - [x] Fetch camera list
- [x] Check permissions and ask if not granted - **currently shows instructions but not prompt**
  - [ ] Don't require app to restart once permission has been granted
- [x] Test and tweak haptic feedback (**CAVEAT**: Works on Pixel 5a, but not Samsung Galaxy A12)
- [ ] UI Improvements
  - [x] Non-stretched camera preview
    - [x] Make it 16:9
  - [x] Timer
  - [x] Feedback on successful recording
    - [x] Haptics
    - [x] Visual feedback
  - [ ] Home page, tutorial?
  - [x] Recording sessions (esp. if we have a lot of one-handed signs)
    - [x] Random selection, topics, etc.?
    - [x] **Option to delete a botched recording** (better for us too!)
    - [x] Save all recordings to Google Photos on session end
  - [x] Intermediate screen to let users know when they will stop recording
  - [x] All-done screen once all needed phrases are recorded
- [x] Persistence? (Store which signs users have already recorded so they don't need to re-record)
- [x] Multitasking support (current version may crash on exit/reopen)
- [x] Easy access to video files (upload? direct file browser?) — **copy to Downloads folder**
  - [x] Upload strategy for videos (upload to Google Photos - preserves file name and uploads automatically), however there is a toggle that must be flipped on in the Google Photos Android
  app to enable automatic uploads. Should be done before shipping devices out.
- [x] Multiuser support (includes user information in saved files)
- [x] App asks for camera and storage permission before starting
    - [x] App handles camera and storage permission denial gracefully
- [x] Support Android 11+ external writing
- [x] General bugfixing (no crashes)


## Notes
* To run this app in the Android emulator, you may need to explicitly enable the
webcam. In Android Studio, go to `Tools` > `AVD Manager`, click the pencil
(edit) button for the emulator of your choice, press `Show Advanced Settings`,
and choose your webcam (likely `Webcam0`) under `Camera` > `Front`. Press
`Finish` to save.

* If you encounter issues where the entire Android emulator crashes when trying
to run the app, go to the same window as above, press `Show Advanced Settings`,
and set `Emulated Performance` > `Graphics` to `Software`.
