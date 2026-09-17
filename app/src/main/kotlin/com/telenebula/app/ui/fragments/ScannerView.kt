package com.telenebula.app.ui.fragments

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors

/** QR viewfinder: CameraX preview plus an ML Kit analyzer that reports each decoded QR value. */
@Composable
fun ScannerView(onScanned: (String) -> Unit, onError: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestScanned = rememberUpdatedState(onScanned)
    val latestError = rememberUpdatedState(onError)
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val providerFuture = ProcessCameraProvider.getInstance(context)
        // the listener and onDispose both run on mainExecutor (the main thread), so a plain flag
        // is enough to stop a provider resolution that lands after teardown from rebinding a
        // shut-down analyzerExecutor — CameraX would deliver a frame to it from its own internal
        // thread and an uncaught RejectedExecutionException there kills the whole process.
        var isDisposed = false
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        providerFuture.addListener({
            if (isDisposed) return@addListener
            // ProcessCameraProvider.get() can throw (HAL init failure, no camera on the device);
            // it must stay inside the same runCatching as bindToLifecycle or it crashes the app
            // on this listener's (main) thread with nothing to catch it.
            val outcome = runCatching {
                val p = providerFuture.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(
                    analyzerExecutor,
                    MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, mainExecutor) { result ->
                        result.getValue(scanner)?.firstOrNull()?.rawValue?.let { latestScanned.value(it) }
                    },
                )
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            }
            outcome.onFailure { latestError.value() }
        }, mainExecutor)
        onDispose {
            isDisposed = true
            providerFuture.cancel(false)
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            // close the scanner as the last task queued on its own executor so it never races
            // an in-flight detection still running when teardown starts
            analyzerExecutor.execute { scanner.close() }
            analyzerExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
