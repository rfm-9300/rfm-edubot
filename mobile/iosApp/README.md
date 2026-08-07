# iOS and TestFlight

`project.yml` is the source of truth for the Xcode project. Regenerate it after changing the
project definition:

```bash
xcodegen generate --spec project.yml
```

## Run on a simulator or device

1. Open `EduBot.xcodeproj` in Xcode.
2. Select the `EduBot` target and set your Apple Developer Team in Signing & Capabilities.
3. Keep the bundle identifier unique. The default is `pt.thebotslab.edubot`.
4. Select a simulator or connected iPhone and run the app.

The Xcode build phase invokes `:shared:embedAndSignAppleFrameworkForXcode`, which builds and
embeds the Kotlin/Compose framework for the selected Apple architecture.

## TestFlight

1. In Xcode, select `Product > Archive` with `Any iOS Device` selected.
2. In Organizer, select `Distribute App > App Store Connect > Upload`.
3. Wait for processing in App Store Connect, then open the app's TestFlight tab.
4. Add an internal tester or create an external tester group with your friend's Apple ID email.
5. Your friend installs Apple's TestFlight app and accepts the email invitation.

External testers require Apple beta review for the first build. Do not distribute an APK or a
manually exported IPA to an iPhone.
