package com.openauto.dash.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.openauto.dash.link.CarRegistration
import com.openauto.dash.link.CritAir
import com.openauto.dash.link.Energies
import com.openauto.dash.link.RegistrationReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The car's registration certificate, scanned here: on-device text
 * recognition reads the photo, [RegistrationReader] picks the fields out,
 * the driver checks them, and only those fields go to the car (its profile
 * then knows the plate, the VIN, the first registration and the Crit'Air
 * sticker). The photo never leaves the phone and is deleted once read.
 */

/** What the card shows. */
private sealed interface Scan {
    data object Idle : Scan
    data object Reading : Scan
    data object Nothing : Scan
    data object NoRecognizer : Scan
    data class Read(val registration: CarRegistration) : Scan
}

/** The photo is read at most this long a side: enough for the small print, quick to recognize. */
private const val READ_SIDE = 2400

@Composable
internal fun RegistrationCard(connected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scan by remember { mutableStateOf<Scan>(Scan.Idle) }
    var sent by rememberSaveable { mutableStateOf(false) }

    fun read(uri: Uri) {
        scan = Scan.Reading
        sent = false
        scope.launch {
            scan = runCatching { recognize(context, uri) }.fold(
                onSuccess = { if (it.empty) Scan.Nothing else Scan.Read(it) },
                onFailure = { if (it is RecognizerMissing) Scan.NoRecognizer else Scan.Nothing }
            )
            withContext(Dispatchers.IO) { photoFile(context).delete() }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) read(photoUri(context))
    }
    val cameraAllowed = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camera.launch(photoUri(context))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) read(uri)
    }
    val openCamera = {
        // The QR scanner declares the camera, so the camera app may only be used once it is allowed.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.launch(photoUri(context))
        } else {
            cameraAllowed.launch(Manifest.permission.CAMERA)
        }
    }
    val openGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val current = scan
    val line = when (current) {
        Scan.Reading -> stringResource(R.string.reg_reading)
        Scan.Nothing -> stringResource(R.string.reg_nothing)
        Scan.NoRecognizer -> stringResource(R.string.reg_no_ocr)
        is Scan.Read -> when {
            sent -> stringResource(R.string.reg_sent)
            !connected -> stringResource(R.string.reg_offline)
            else -> stringResource(R.string.reg_check)
        }
        Scan.Idle -> stringResource(R.string.reg_detail)
    }
    val tint = when {
        current is Scan.Read && sent -> CompanionColors.Teal
        current == Scan.Nothing || current == Scan.NoRecognizer -> CompanionColors.Amber
        else -> CompanionColors.Blue
    }
    Panel {
        Column(Modifier.padding(18.dp)) {
            CardHeading(Icons.Filled.Badge, stringResource(R.string.reg_title), line, tint)
            if (current is Scan.Read) {
                Fields(current.registration) { scan = Scan.Read(it); sent = false }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            LinkServer.send(current.registration)
                            sent = true
                        },
                        enabled = connected && !current.registration.empty
                    ) { Text(stringResource(R.string.reg_send)) }
                    TextButton(onClick = openCamera) { Text(stringResource(R.string.reg_again)) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val reading = current == Scan.Reading
                    Button(onClick = openCamera, enabled = !reading) { Text(stringResource(R.string.reg_scan)) }
                    OutlinedButton(onClick = openGallery, enabled = !reading) { Text(stringResource(R.string.reg_choose)) }
                    if (reading) {
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CompanionColors.Blue)
                    }
                }
            }
        }
    }
}

/** What was read, to check and put right before sending. */
@Composable
private fun Fields(r: CarRegistration, onChange: (CarRegistration) -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.reg_plate), r.plate, Modifier.weight(1f)) { onChange(r.copy(plate = it.uppercase())) }
            Field(stringResource(R.string.reg_first), r.firstRegistration, Modifier.weight(1f)) { typed ->
                // Kept as typed only once it is a date; emptied, it is dropped.
                val date = RegistrationReader.date(typed)
                onChange(r.copy(firstRegistration = date?.toString() ?: typed.trim()))
            }
        }
        Field(stringResource(R.string.reg_vin), r.vin) { onChange(r.copy(vin = it.uppercase().filter(Char::isLetterOrDigit).take(17))) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.reg_make), r.make, Modifier.weight(1f)) { onChange(r.copy(make = it)) }
            Field(stringResource(R.string.reg_model), r.model, Modifier.weight(1f)) { onChange(r.copy(model = it)) }
        }
        Field(stringResource(R.string.reg_next_inspection), r.nextInspection) { typed ->
            onChange(r.copy(nextInspection = RegistrationReader.date(typed)?.toString() ?: typed.trim()))
        }
        val facts = listOfNotNull(
            r.powerKw?.let { stringResource(R.string.reg_power, it, (it * 1.35962).roundToInt()) },
            CritAir.of(Energies.of(r.energy), RegistrationReader.date(r.firstRegistration), r.euro)?.let {
                if (it == CritAir.UNCLASSED) stringResource(R.string.reg_crit_air_unclassed) else stringResource(R.string.reg_crit_air, it)
            }
        )
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("  ·  "), color = CompanionColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier)
}

/** Play services' text recognition isn't on this phone yet (still downloading, or no Play services). */
private class RecognizerMissing(cause: Throwable) : Exception(cause)

/** The certificate's fields read off the photo at [uri]. */
private suspend fun recognize(context: Context, uri: Uri): CarRegistration {
    val bitmap = withContext(Dispatchers.IO) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > READ_SIDE) {
                val scale = READ_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
    }
    val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val lines = suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    cont.resume(text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        RegistrationReader.Line(line.text, box.left, box.top, box.right, box.bottom)
                    })
                }
                .addOnFailureListener {
                    // Only the recognizer's absence is told apart; any other failure reads as nothing found.
                    cont.resumeWithException(if (it is MlKitException && it.errorCode == MlKitException.UNAVAILABLE) RecognizerMissing(it) else it)
                }
        }
        return withContext(Dispatchers.Default) { RegistrationReader.read(lines) }
    } finally {
        client.close()
        bitmap.recycle()
    }
}

/** Where the camera app puts the certificate's photo; deleted once read. */
private fun photoFile(context: Context): File = File(File(context.cacheDir, "registration").apply { mkdirs() }, "photo.jpg")

private fun photoUri(context: Context): Uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", photoFile(context))
