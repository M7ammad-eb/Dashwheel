package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PropaneTank
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** The sections of the action picker, in the order they're shown. */
internal enum class WheelActionGroup(@StringRes val labelRes: Int) {
    MEDIA(R.string.wheel_group_media),
    VOLUME(R.string.wheel_group_volume),
    CAR(R.string.wheel_group_car),
    NAVIGATION(R.string.wheel_group_navigation),
    SYSTEM(R.string.wheel_group_system),
    PHONE(R.string.wheel_group_phone)
}

/**
 * Every built-in action a learned steering wheel button can be assigned to
 * (see SteeringWheelDialog.kt), plus launching a chosen app ([WheelAssignment.LaunchApp]).
 * Each one only ever touches process-wide state — audio, the media session,
 * the accessibility service, the phone link — so it can run straight from
 * [MainActivity]'s key dispatch, with no Composable in the loop.
 * [needsAccessibility]: works only once Dashwheel is enabled under Settings → Accessibility.
 */
internal enum class SteeringWheelAction(
    val group: WheelActionGroup,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val needsAccessibility: Boolean = false
) {
    MEDIA_PLAY_PAUSE(WheelActionGroup.MEDIA, R.string.wheel_action_play_pause, Icons.Filled.PlayArrow),
    MEDIA_NEXT(WheelActionGroup.MEDIA, R.string.wheel_action_next, Icons.Filled.SkipNext),
    MEDIA_PREVIOUS(WheelActionGroup.MEDIA, R.string.wheel_action_previous, Icons.Filled.SkipPrevious),

    VOLUME_UP(WheelActionGroup.VOLUME, R.string.wheel_action_volume_up, Icons.AutoMirrored.Filled.VolumeUp),
    VOLUME_DOWN(WheelActionGroup.VOLUME, R.string.wheel_action_volume_down, Icons.AutoMirrored.Filled.VolumeDown),
    VOLUME_MUTE(WheelActionGroup.VOLUME, R.string.wheel_action_mute, Icons.AutoMirrored.Filled.VolumeOff),

    // What the car tells and does without a look at the screen.
    SAY_STATUS(WheelActionGroup.CAR, R.string.wheel_action_status, Icons.Filled.RecordVoiceOver),
    QUIET(WheelActionGroup.CAR, R.string.wheel_action_quiet, Icons.Filled.NotificationsOff),
    DISMISS_ALERT(WheelActionGroup.CAR, R.string.wheel_action_dismiss_alert, Icons.Filled.Done),

    NAVIGATE_HOME(WheelActionGroup.NAVIGATION, R.string.wheel_action_navigate_home, Icons.Filled.Home),
    NAVIGATE_WORK(WheelActionGroup.NAVIGATION, R.string.wheel_action_navigate_work, Icons.Filled.Work),
    NAVIGATE_FUEL(WheelActionGroup.NAVIGATION, R.string.wheel_action_navigate_fuel, Icons.Filled.LocalGasStation),

    GO_HOME(WheelActionGroup.SYSTEM, R.string.wheel_action_home, Icons.Filled.Home),
    SYSTEM_BACK(WheelActionGroup.SYSTEM, R.string.wheel_action_back, Icons.AutoMirrored.Filled.ArrowBack, needsAccessibility = true),
    RECENT_APPS(WheelActionGroup.SYSTEM, R.string.wheel_action_recents, Icons.Filled.ViewAgenda, needsAccessibility = true),
    OPEN_APPS(WheelActionGroup.SYSTEM, R.string.wheel_action_open_apps, Icons.Filled.Apps),
    NEXT_DASHBOARD(WheelActionGroup.SYSTEM, R.string.wheel_action_next_dashboard, Icons.AutoMirrored.Filled.NavigateNext),
    PREVIOUS_DASHBOARD(WheelActionGroup.SYSTEM, R.string.wheel_action_previous_dashboard, Icons.AutoMirrored.Filled.NavigateBefore),
    DASHBOARD_UP(WheelActionGroup.SYSTEM, R.string.wheel_action_dashboard_up, Icons.Filled.KeyboardArrowUp),
    DASHBOARD_DOWN(WheelActionGroup.SYSTEM, R.string.wheel_action_dashboard_down, Icons.Filled.KeyboardArrowDown),
    SECOND_SCREEN_NEXT(WheelActionGroup.SYSTEM, R.string.wheel_action_second_screen_next, Icons.Filled.Cast),
    SECOND_SCREEN_PREVIOUS(WheelActionGroup.SYSTEM, R.string.wheel_action_second_screen_previous, Icons.Filled.Cast),
    NOTIFICATIONS(WheelActionGroup.SYSTEM, R.string.wheel_action_notifications, Icons.Filled.Notifications, needsAccessibility = true),
    QUICK_SETTINGS(WheelActionGroup.SYSTEM, R.string.wheel_action_quick_settings, Icons.Filled.Tune, needsAccessibility = true),
    VOICE_ASSISTANT(WheelActionGroup.SYSTEM, R.string.wheel_action_voice, Icons.Filled.Mic),
    TOGGLE_SPLIT(WheelActionGroup.SYSTEM, R.string.wheel_action_toggle_split, Icons.Filled.Splitscreen, needsAccessibility = true),
    SWAP_SPLIT(WheelActionGroup.SYSTEM, R.string.wheel_action_swap_split, Icons.Filled.SwapHoriz, needsAccessibility = true),
    SCREENSHOT(WheelActionGroup.SYSTEM, R.string.wheel_action_screenshot, Icons.Filled.Screenshot, needsAccessibility = true),

    READ_MESSAGE(WheelActionGroup.PHONE, R.string.wheel_action_read_message, Icons.AutoMirrored.Filled.Message),
    ANSWER_CALL(WheelActionGroup.PHONE, R.string.wheel_action_answer, Icons.Filled.Call),
    DECLINE_CALL(WheelActionGroup.PHONE, R.string.wheel_action_decline, Icons.Filled.CallEnd),
    HANG_UP_CALL(WheelActionGroup.PHONE, R.string.wheel_action_hang_up, Icons.Filled.CallEnd),

    // Names are saved with the learned buttons: new ones go last, whatever their group.
    ASK_MECHANIC(WheelActionGroup.CAR, R.string.ai_ask_wheel_action, Icons.Filled.QuestionAnswer),
    GEMINI_LIVE(WheelActionGroup.CAR, R.string.ai_gemini_live, Icons.Filled.AutoAwesome),
    STOP_GUIDANCE(WheelActionGroup.NAVIGATION, R.string.wheel_action_stop_guidance, Icons.Filled.Close),
    LPG_FULL(WheelActionGroup.CAR, R.string.lpg_wheel_full, Icons.Filled.PropaneTank),
    LPG_SWITCH(WheelActionGroup.CAR, R.string.lpg_wheel_switch, Icons.Filled.SwapHoriz),
    SCREEN_FOCUS(WheelActionGroup.SYSTEM, R.string.wheel_action_screen_focus, Icons.Filled.ConnectedTv),
    // Play and pause on their own, for a pair of keys such as an FYT unit's mute on / mute off (#148).
    MEDIA_PLAY(WheelActionGroup.MEDIA, R.string.wheel_action_play, Icons.Filled.PlayArrow),
    MEDIA_PAUSE(WheelActionGroup.MEDIA, R.string.wheel_action_pause, Icons.Filled.Pause);

    fun run(context: Context) {
        when (this) {
            // Straight to the player when one is known, as the media tile does: on some
            // firmwares (FYT) a media key Dashwheel sends never reaches it (#148).
            MEDIA_PLAY_PAUSE -> player(context)?.playPause() ?: dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            MEDIA_PLAY -> player(context)?.play() ?: dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            MEDIA_PAUSE -> player(context)?.pause() ?: dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            MEDIA_NEXT -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            MEDIA_PREVIOUS -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            VOLUME_UP -> MediaVolume.raise(context, show = true)
            VOLUME_DOWN -> MediaVolume.lower(context, show = true)
            VOLUME_MUTE -> MediaVolume.toggleMute(context, show = true)
            SAY_STATUS -> StartupBriefing.sayStatus(context)
            QUIET -> toggleQuiet(context)
            // Seen, heard: the red chips, the door, tyre and message cards go (a new problem comes back),
            // and the voice stops mid-sentence.
            DISMISS_ALERT -> {
                AlertCenter.acknowledgeAll()
                DoorAlertOverlay.dismiss()
                TyreAlertOverlay.dismiss()
                MessageAlerts.dismiss()
                CarVoice.stop()
            }
            NAVIGATE_HOME -> guideTo(context, home = true)
            NAVIGATE_WORK -> guideTo(context, home = false)
            NAVIGATE_FUEL -> HandsFree.navigateToFuel(context)
            // Pressed over Maps or the radio too: the dashboard comes in front first.
            GO_HOME -> if (!showDashboard(context, home = true)) MainActivity.homePressed.value = System.currentTimeMillis()
            // In an app inside a tile (touched lately): its Back, on its tile. Else the dashboard's.
            SYSTEM_BACK -> if (!EmbeddedApp.backInTile()) SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            RECENT_APPS -> SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            OPEN_APPS -> {
                showDashboard(context)
                MainActivity.openAppsRequested.value = System.currentTimeMillis()
            }
            // Behind another app, the first press shows the dashboard as it was left.
            // With the focus on the second screen (ScreenFocus), its cluster pages turn instead, one row of them.
            NEXT_DASHBOARD -> if (!ScreenFocus.step(1) && !showDashboard(context)) MainActivity.dashboardStep.value = System.currentTimeMillis() to 1
            PREVIOUS_DASHBOARD -> if (!ScreenFocus.step(-1) && !showDashboard(context)) MainActivity.dashboardStep.value = System.currentTimeMillis() to -1
            DASHBOARD_UP -> if (!ScreenFocus.step(-1) && !showDashboard(context)) MainActivity.dashboardClimb.value = System.currentTimeMillis() to -1
            DASHBOARD_DOWN -> if (!ScreenFocus.step(1) && !showDashboard(context)) MainActivity.dashboardClimb.value = System.currentTimeMillis() to 1
            // With a button that switches the focus, the second screen's page buttons follow it too: on screen 1 they move its dashboards.
            SECOND_SCREEN_NEXT -> if (ScreenFocus.unitFocused()) NEXT_DASHBOARD.run(context) else SecondScreenController.turnPage(1)
            SECOND_SCREEN_PREVIOUS -> if (ScreenFocus.unitFocused()) PREVIOUS_DASHBOARD.run(context) else SecondScreenController.turnPage(-1)
            NOTIFICATIONS -> SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            QUICK_SETTINGS -> SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            VOICE_ASSISTANT -> startVoiceAssistant(context)
            TOGGLE_SPLIT -> SplitAccessibilityService.requestSplit()
            SWAP_SPLIT -> SplitLauncher.swapSplit()
            SCREENSHOT -> SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            READ_MESSAGE -> AlertVoice.readLastMessage(context)
            // The call wherever it is: the companion's, or the head unit's own Bluetooth.
            ANSWER_CALL -> PhoneCallOverlay.answerCall()
            DECLINE_CALL -> PhoneCallOverlay.declineCall()
            HANG_UP_CALL -> PhoneCallOverlay.hangUpCall()
            // Asked out loud, answered out loud from the live readings; a second press sends.
            ASK_MECHANIC -> AskMechanic.listenHandsFree(context)
            GEMINI_LIVE -> GeminiLive.toggle(context)
            STOP_GUIDANCE -> stopGuidance(context)
            LPG_FULL -> LpgTank.fillUpHandsFree(context)
            LPG_SWITCH -> LpgTank.switchHandsFree(context)
            SCREEN_FOCUS -> ScreenFocus.toggle(context)
        }
    }

    /** Ends the guidance, the dashboard's own and Google Maps', and says which it was: stopped, or none to stop. */
    private fun stopGuidance(context: Context) {
        val own = InAppNav.endNow()
        val maps = NavDirections.exitNavigation()
        HandsFree.say(context, if (own || maps) R.string.voice_guidance_stopped else R.string.voice_guidance_none)
    }

    /**
     * Brings the dashboard in front when another app covers it; [home] also
     * takes it back to its home page, as the Home key does. False when it was
     * in front already, and nothing was done.
     */
    private fun showDashboard(context: Context, home: Boolean = false): Boolean {
        if (FreeformBar.dashboardInFront) return false
        EmbeddedApp.userActed()
        val intent = Intent().setComponent(FloatingHome.component(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_KEEP_PAGE, !home)
        return context.launchSafely(intent)
    }

    /** Guidance home or to work, or said out loud that it isn't set: the button never seems dead. */
    private fun guideTo(context: Context, home: Boolean) {
        PlacesStore.load(context)
        val places = PlacesStore.places.value
        val place = if (home) places.home else places.work
        when {
            place == null -> HandsFree.say(context, if (home) R.string.places_no_home else R.string.places_no_work)
            !NavHandoff.start(context, place) -> HandsFree.say(context, R.string.places_no_nav_app)
        }
    }

    /** Quiet for this drive, or the voice back: said either way, since nobody looks. */
    private fun toggleQuiet(context: Context) {
        val on = !CarVoice.quiet
        CarVoice.quiet = on
        HandsFree.say(context, if (on) R.string.voice_quiet_on else R.string.voice_quiet_off)
    }

    /**
     * Head units differ in which assistant entry point they have, if any: the
     * first one an app answers is started, and none at all is said out loud
     * rather than the button seeming dead.
     */
    private fun startVoiceAssistant(context: Context) {
        val started = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
            Intent(Intent.ACTION_ASSIST),
            Intent(RecognizerIntent.ACTION_WEB_SEARCH)
        ).any { context.launchSafely(it) }
        if (!started) Toast.makeText(context, R.string.wheel_no_voice_assistant, Toast.LENGTH_SHORT).show()
    }

    /** Routed to whichever app holds the active media session, exactly like a hardware media button. */
    /** The shared media controller when it has a player to steer, else null (the media key then). */
    private fun player(context: Context): CarMediaController? =
        CarMediaController.shared(context).takeIf { it.steering }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        val audio = MediaVolume.audio(context)
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            runCatching { audio.dispatchMediaKeyEvent(KeyEvent(action, keyCode)) }
        }
    }
}

/** What a learned button does: a built-in action, or launching one app. */
internal sealed class WheelAssignment {
    data class Preset(val action: SteeringWheelAction) : WheelAssignment()
    data class LaunchApp(val packageName: String, val appLabel: String) : WheelAssignment()
}

/** The launcher icon for a [WheelAssignment.LaunchApp], or null once the app is gone. */
internal fun loadAppIcon(context: Context, packageName: String) =
    runCatching { context.packageManager.getApplicationIcon(AppLauncher.packageOf(packageName)) }.getOrNull()
