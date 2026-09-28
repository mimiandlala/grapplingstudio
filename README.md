# Grappling Screen Studio

A **separate** Android application (`com.kentaiwamoto.grapplingstudio`). It does not update or overwrite Screen Ink DJI (`com.kentaiwamoto.screenink`) or the original DJI screen recorder.

## Features

- Requests Android **default-display / entire-screen** recording only. Device manufacturers may override the request.
- Select and adjust a crop rectangle over the actual app/video before pressing the floating **Record** button.
- Live red pen, Clear ink, Draw/Touch switching. In entire-screen mode the live ink is captured in the original video. The crop export does not reconstruct old strokes, so Clear does not retroactively erase them.
- Movable, resizable front camera window. Drag anywhere on the face preview to move; drag its bottom-right corner to resize. Tap Camera to hide/show it. Keep it **inside the yellow rectangle** to include it in the cropped MP4.
- Built-in local video player. Open a local grappling video using Android's file picker. Its play/pause, ±10 s, and seek controls sit below the video view. If you select only the video picture in the yellow rectangle, the cropped recording excludes player controls. Use the floating Touch button to control playback, and Draw to annotate.
- DJI Bluetooth microphone commentary. The local video's system audio is not mixed into the recording; use the player's audio carefully if you do not want the mic to hear the speaker.
- Saves an untouched full-display MP4 first to `Movies/Grappling Screen Studio/Originals`; saves a cropped copy to `Movies/Grappling Screen Studio/Crops` after export. If the crop fails, the original remains.
- Requests 60 fps/32 Mbps H.264 raw video and 24 Mbps for crop output. Actual frame rate depends on Android and content. A crop cannot contain more real pixels than the selected part of the display.

## Install on your phone

1. Create a **new GitHub repository** (for example `grappling-screen-studio`). Keep your existing repository and app intact.
2. Upload the contents of this ZIP to the repository root (not an extra enclosing folder). GitHub Codespaces can unzip this ZIP into the repository root.
3. GitHub Actions builds `app-debug.apk` from `.github/workflows/build-apk.yml`. Download the artifact ZIP, extract `app-debug.apk`, and install it. The new app has a different application ID and installs beside your existing apps.
4. Grant camera, microphone, and Display over other apps permissions. Connect the DJI Bluetooth microphone before recording.
5. Open your video and cue it; prepare the recorder; position the yellow rectangle and face box; tap floating **Record**. Play the video with the controls below the rectangle. Use **Touch** to operate the phone or **Draw** to annotate. Stop using the floating button or notification.
6. Check the cropped MP4 and original in the Gallery or My Files before important use. Hardware camera-overlay capture and audio routing must be tested on the phone.

This is source code; no prebuilt or signed release APK is in this ZIP. Android camera/display APIs can behave differently across phones. The GitHub Actions build verifies compilation, and phone tests verify the live camera overlay and media capture.
