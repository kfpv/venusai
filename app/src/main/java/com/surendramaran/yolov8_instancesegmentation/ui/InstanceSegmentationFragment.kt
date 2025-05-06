package com.surendramaran.yolov8_instancesegmentation.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.surendramaran.yolov8_instancesegmentation.BuildConfig
import com.surendramaran.yolov8_instancesegmentation.Constants.LABELS_PATH
import com.surendramaran.yolov8_instancesegmentation.Constants.MODEL_PATH
import com.surendramaran.yolov8_instancesegmentation.R
import com.surendramaran.yolov8_instancesegmentation.databinding.DialogSettingsBinding
import com.surendramaran.yolov8_instancesegmentation.databinding.FragmentInstanceSegmentationBinding
import com.surendramaran.yolov8_instancesegmentation.ml.DrawImages
import com.surendramaran.yolov8_instancesegmentation.ml.InstanceSegmentation
import com.surendramaran.yolov8_instancesegmentation.ml.Success
import com.surendramaran.yolov8_instancesegmentation.utils.OrientationLiveData
import com.surendramaran.yolov8_instancesegmentation.utils.Utils
import com.surendramaran.yolov8_instancesegmentation.utils.Utils.addCarouselEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class InstanceSegmentationFragment : Fragment() {
    private var _binding: FragmentInstanceSegmentationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null
    private lateinit var orientationLiveData: OrientationLiveData

    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private lateinit var drawImages: DrawImages

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null // To control camera features like flash and zoom
    private var isFlashlightOn: Boolean = false
    private val ZOOM_LEVEL_1X = 1.0f
    private val ZOOM_LEVEL_TOGGLE = 2.0f // Define a toggle zoom level, e.g., 2x

    // Flag to control the capture loop
    private var shouldContinueCapture = false

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCameraAndTakePhoto()
            } else {
                toast("Camera permission denied.")
            }
        }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let {
            val bitmap = Utils.getBitmapFromUri(requireContext(), it) ?: return@let
            val rotated = Utils.rotateImageIfRequired(requireContext(), bitmap, it)
            // Stop continuous capture if user picks from gallery
            shouldContinueCapture = false
            runInstanceSegmentation(rotated)
        }
    }

    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(Utils.getCameraId(cameraManager))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInstanceSegmentationBinding.inflate(inflater, container, false)
        viewPagerAdapter = ViewPagerAdapter(mutableListOf())
        binding.viewpager.adapter = viewPagerAdapter
        binding.viewpager.addCarouselEffect()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // If returning to the fragment and camera was set up, restart capture if it was running
        if (imageCapture != null && !shouldContinueCapture) {
            // Check permissions again in case they were revoked while paused
            // For simplicity, we assume permissions are still granted if imageCapture is not null.
            // A more robust check might be needed.
            // shouldContinueCapture = true; // Decide if you want to auto-restart loop on resume
            // takePhoto();
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop continuous capture when the fragment is paused
        shouldContinueCapture = false
    }

    override fun onDestroyView() {
        shouldContinueCapture = false // Ensure loop stops
        _binding = null
        super.onDestroyView()
        cameraExecutor.shutdown()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        instanceSegmentation = InstanceSegmentation(
            context = requireContext(),
            modelPath = MODEL_PATH,
            labelPath = LABELS_PATH
        ) {
            toast(it)
        }

        drawImages = DrawImages(requireContext())

        orientationLiveData = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner) { orientation ->
                Log.d(InstanceSegmentationFragment::class.java.simpleName, "Orientation changed: $orientation")
                // Optionally, reconfigure imageCapture target rotation if needed
                imageCapture?.targetRotation = binding.root.display.rotation
            }
        }

        bindListeners() // Call bindListeners first
        requestCameraPermissionAndTakePhoto() // Then request permission and start camera
    }

    private fun requestCameraPermissionAndTakePhoto() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCameraAndTakePhoto()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                toast("Camera permission is required to take photos.")
                activityResultLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                activityResultLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraAndTakePhoto() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(binding.root.display.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                // Store the camera instance
                this.camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageCapture
                )

                // Observe torch state to keep UI consistent if changed externally
                this.camera?.cameraInfo?.torchState?.observe(viewLifecycleOwner) { state ->
                    isFlashlightOn = state == TorchState.ON
                    updateFlashlightButtonIcon()
                }
                // Observe zoom state to keep UI consistent
                this.camera?.cameraInfo?.zoomState?.observe(viewLifecycleOwner) { zoomState ->
                    updateZoomButtonIcon()
                }

                // Initial UI update for buttons after camera is ready
                updateFlashlightButtonIcon()
                updateZoomButtonIcon()

                shouldContinueCapture = true
                takePhoto()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                toast("Failed to start camera.")
                shouldContinueCapture = false
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        // Check if the loop should continue and if the view is still available
        if (!shouldContinueCapture || _binding == null || imageCapture == null) {
            Log.d(TAG, "takePhoto: Stopping capture loop. shouldContinueCapture=$shouldContinueCapture, _binding=$_binding, imageCapture=$imageCapture")
            return
        }
        val imageCapture = this.imageCapture ?: return // Re-check for safety

        val photoFile = Utils.createImageFile(requireContext())
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    toast("Photo capture failed: ${exc.message}")
                    // Continue the loop even if one capture fails
                    if (shouldContinueCapture) {
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(1000) // Add a small delay before retrying
                            takePhoto()
                        }
                    }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (_binding == null) { // Check if fragment is still active
                        shouldContinueCapture = false
                        return
                    }
                    val savedUri = Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")

                    val bitmap = Utils.getBitmapFromUri(requireContext(), savedUri)
                    if (bitmap != null) {
                        runInstanceSegmentation(bitmap)
                    } else {
                        toast("Failed to load captured image.")
                        // Continue the loop
                        if (shouldContinueCapture) {
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(1000) // Add a small delay
                                takePhoto()
                            }
                        }
                    }
                }
            }
        )
    }

    private fun bindListeners() {
        binding.apply {
            btnGallery.setOnClickListener {
                shouldContinueCapture = false // Stop loop if gallery is opened
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            ivSettings.setOnClickListener {
                showSettingsDialog()
            }

            btnFlashlight.setOnClickListener {
                camera?.let { cam ->
                    if (cam.cameraInfo.hasFlashUnit()) {
                        val newTorchState = !isFlashlightOn
                        cam.cameraControl.enableTorch(newTorchState).addListener({
                            // isFlashlightOn will be updated by the observer
                        }, ContextCompat.getMainExecutor(requireContext()))
                    } else {
                        toast("Flashlight not available.")
                    }
                } ?: toast("Camera not ready for flashlight.")
            }

            btnZoom.setOnClickListener {
                camera?.let { cam ->
                    cam.cameraInfo.zoomState.value?.let { zoomState ->
                        val minZoom = zoomState.minZoomRatio
                        val maxZoom = zoomState.maxZoomRatio // Max zoom isn't strictly needed for this logic but good to have
                        val currentActualZoom = zoomState.zoomRatio
                        val zoomLevel1X = ZOOM_LEVEL_1X.coerceIn(minZoom, maxZoom) // Ensure 1.0x is valid

                        val tolerance = 0.01f // To handle floating point inaccuracies

                        val targetZoomRatio = when {
                            // If currently zoomed in (significantly greater than 1.0x)
                            currentActualZoom > zoomLevel1X + tolerance -> zoomLevel1X
                            // If currently at 1.0x (or very close to it)
                            Math.abs(currentActualZoom - zoomLevel1X) < tolerance -> minZoom
                            // If currently at minZoom (or very close to it, or less than 1.0x)
                            else -> zoomLevel1X
                        }

                        if (Math.abs(targetZoomRatio - currentActualZoom) > tolerance) {
                            cam.cameraControl.setZoomRatio(targetZoomRatio.coerceIn(minZoom, maxZoom)).addListener({
                                // Zoom state observer will update the button icon
                            }, ContextCompat.getMainExecutor(requireContext()))
                        } else {
                            updateZoomButtonIcon() // Ensure icon is correct if no change
                        }
                    } ?: toast("Zoom state not available.")
                } ?: toast("Camera not ready for zoom.")
            }
            // Initial icon states before camera is fully ready (will be updated again)
            updateFlashlightButtonIcon()
            updateZoomButtonIcon()
        }
    }

    private fun updateFlashlightButtonIcon() {
        if (isFlashlightOn) {
           // binding.btnFlashlight.setIconResource(R.drawable.ic_baseline_flashlight_off_24) // Icon to turn OFF
            binding.btnFlashlight.text = "Flash Off"
        } else {
           // binding.btnFlashlight.setIconResource(R.drawable.ic_baseline_flashlight_on_24)  // Icon to turn ON
            binding.btnFlashlight.text = "Flash On"
        }
    }

    private fun updateZoomButtonIcon() {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val currentActualZoom = zoomState?.zoomRatio ?: ZOOM_LEVEL_1X
        val minZoom = zoomState?.minZoomRatio ?: ZOOM_LEVEL_1X
        val maxZoom = zoomState?.maxZoomRatio ?: ZOOM_LEVEL_TOGGLE // Fallback for maxZoom
        val zoomLevel1X = ZOOM_LEVEL_1X.coerceIn(minZoom, maxZoom)

        val tolerance = 0.01f

        when {
            // If currently zoomed in (significantly greater than 1.0x)
            currentActualZoom > zoomLevel1X + tolerance -> {
                //binding.btnZoom.setIconResource(R.drawable.ic_baseline_zoom_out_24) // Icon to zoom out to 1x
                binding.btnZoom.text = String.format("Zoom %.1fx", zoomLevel1X)
            }
            // If currently at 1.0x (or very close to it)
            Math.abs(currentActualZoom - zoomLevel1X) < tolerance -> {
                //binding.btnZoom.setIconResource(R.drawable.ic_baseline_zoom_out_24) // Icon to zoom out to minZoom
                binding.btnZoom.text = String.format("Zoom %.2fx", minZoom) // Show minZoom with more precision
            }
            // If currently at minZoom (or very close to it, or less than 1.0x)
            else -> {
                //binding.btnZoom.setIconResource(R.drawable.ic_baseline_zoom_in_24) // Icon to zoom in to 1x
                binding.btnZoom.text = String.format("Zoom %.1fx", zoomLevel1X)
            }
        }
    }

    private fun runInstanceSegmentation(bitmap: Bitmap) {
        if (_binding == null) { // Check if fragment is still active
            shouldContinueCapture = false
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val maxDimension = 1024
        val resizedBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        lifecycleScope.launch(Dispatchers.Default) {
            if (_binding == null) { // Check again inside coroutine
                shouldContinueCapture = false
                if (resizedBitmap != bitmap && !resizedBitmap.isRecycled) resizedBitmap.recycle()
                if (!bitmap.isRecycled) bitmap.recycle() // Recycle original if it's different and not already recycled
                return@launch
            }
            try {
                val bitmapToProcess = resizedBitmap.copy(resizedBitmap.config, false)

                instanceSegmentation?.invoke(
                    frame = bitmapToProcess,
                    smoothEdges = viewModel.isSmoothEdges,
                    onSuccess = { successResult ->
                        processSuccessResult(bitmapToProcess, successResult)
                    },
                    onFailure = { errorMsg -> clearOutput(errorMsg) }
                )
            } catch (e: OutOfMemoryError) {
                clearOutput("Out of memory error: Please try a smaller image or reduce resolution.")
                if (!resizedBitmap.isRecycled && resizedBitmap != bitmap) resizedBitmap.recycle()
                // Original bitmap (passed to runInstanceSegmentation) is recycled by the caller of runInstanceSegmentation if needed
            } catch (e: Exception) {
                Log.e(TAG, "Error in runInstanceSegmentation: ${e.message}", e)
                clearOutput("Error during processing: ${e.message}")
                if (!resizedBitmap.isRecycled && resizedBitmap != bitmap) resizedBitmap.recycle()
            }
        }
    }

    private fun processSuccessResult(original: Bitmap, success: Success) {
        if (_binding == null) { // Check if fragment is still active
            shouldContinueCapture = false
            if (!original.isRecycled) original.recycle()
            return
        }
        requireActivity().runOnUiThread {
            if (_binding == null) { // Check again on UI thread
                shouldContinueCapture = false
                if (!original.isRecycled) original.recycle()
                return@runOnUiThread
            }
            binding.apply {
                tvPreProcess.text = getString(R.string.interface_time_value, success.preProcessTime.toString())
                tvInterfaceTime.text = getString(R.string.interface_time_value, success.interfaceTime.toString())
                tvPostProcess.text = getString(R.string.interface_time_value, success.postProcessTime.toString())
            }

            val images = drawImages.invoke(
                original = original, // This is bitmapToProcess
                success = success,
                isSeparateOut = viewModel.isSeparateOutChecked,
                isMaskOut = viewModel.isMaskOutChecked
            )
            viewPagerAdapter.updateImages(images) // ViewPagerAdapter should handle recycling of these new images

            // Continue the loop
            if (shouldContinueCapture) {
                takePhoto()
            } else {
                if (!original.isRecycled) original.recycle() // If loop stopped, recycle the processed bitmap
            }
        }
    }

    private fun clearOutput(error: String) {
        if (_binding == null) { // Check if fragment is still active
            shouldContinueCapture = false
            return
        }
        requireActivity().runOnUiThread {
            if (_binding == null) { // Check again on UI thread
                shouldContinueCapture = false
                return@runOnUiThread
            }
            binding.apply {
                tvPreProcess.text = getString(R.string.empty_string)
                tvInterfaceTime.text = getString(R.string.empty_string)
                tvPostProcess.text = getString(R.string.empty_string)
            }
            viewPagerAdapter.updateImages(mutableListOf())
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()

            // Continue the loop
            if (shouldContinueCapture) {
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(500) // Small delay after error
                    takePhoto()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldContinueCapture = false // Final stop
        instanceSegmentation?.close()
    }

    private fun showSettingsDialog() {
        val wasCapturing = shouldContinueCapture
        shouldContinueCapture = false // Pause capture

        val dialog = Dialog(requireContext())
        val customDialogBoxBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(customDialogBoxBinding.root)
        dialog.setCancelable(true)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        customDialogBoxBinding.apply {

            cbSeparateOut.isChecked = viewModel.isSeparateOutChecked
            cbMaskOut.isChecked = viewModel.isMaskOutChecked
            cbSmoothEdges.isChecked = viewModel.isSmoothEdges

            cbSeparateOut.setOnCheckedChangeListener { _, isChecked ->
                viewModel.isSeparateOutChecked = isChecked
            }

            cbMaskOut.setOnCheckedChangeListener { _, isChecked ->
                viewModel.isMaskOutChecked = isChecked
            }

            cbSmoothEdges.setOnCheckedChangeListener { _, isChecked ->
                viewModel.isSmoothEdges = isChecked
            }

            llPatreon.setOnClickListener {
                val webpage: Uri = Uri.parse("https://www.patreon.com/SurendraMaran")
                val intent = Intent(Intent.ACTION_VIEW, webpage)
                startActivity(intent)
            }
        }
        dialog.setOnDismissListener {
            // Resume capture if it was active before opening settings
            if (wasCapturing && _binding != null && imageCapture != null && camera != null) {
                shouldContinueCapture = true
                // Re-update button states as camera might have been reset or changed
                updateFlashlightButtonIcon()
                updateZoomButtonIcon()
                takePhoto()
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        if (_binding == null) return
        lifecycleScope.launch(Dispatchers.Main) {
            if (_binding == null) return@launch // Check again
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "InstanceSegmentationFragment"
    }
}