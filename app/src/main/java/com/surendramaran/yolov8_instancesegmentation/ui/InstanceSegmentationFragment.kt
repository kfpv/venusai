package com.surendramaran.yolov8_instancesegmentation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.surendramaran.yolov8_instancesegmentation.Constants
import com.surendramaran.yolov8_instancesegmentation.R
import com.surendramaran.yolov8_instancesegmentation.databinding.DialogSettingsBinding
import com.surendramaran.yolov8_instancesegmentation.databinding.FragmentInstanceSegmentationBinding
import com.surendramaran.yolov8_instancesegmentation.ml.InstanceSegmentation
import com.surendramaran.yolov8_instancesegmentation.ml.Success
import com.surendramaran.yolov8_instancesegmentation.utils.YuvToRgbConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InstanceSegmentationFragment : Fragment() {
    private var _binding: FragmentInstanceSegmentationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null

    // CameraX variables
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var bitmapBuffer: Bitmap
    private lateinit var converter: YuvToRgbConverter

    // Add reference to OverlayView
    private lateinit var overlayView: OverlayView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInstanceSegmentationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlayView = binding.overlayView // Initialize OverlayView reference

        instanceSegmentation = InstanceSegmentation(
            context = requireContext(),
            modelPath = Constants.MODEL_PATH,
            labelPath = Constants.LABELS_PATH
        ) {
            toast(it)
        }

        // Initialize CameraX components
        cameraExecutor = Executors.newSingleThreadExecutor()
        converter = YuvToRgbConverter(requireContext())
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        // Check for camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        bindListeners()
    }

    private fun bindListeners() {
        binding.ivSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun startCamera() {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Adjust as needed
            .build()

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview.setSurfaceProvider(binding.previewView.surfaceProvider)

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Match preview aspect ratio
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Common format
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { image ->
                    if (!::bitmapBuffer.isInitialized) {
                        bitmapBuffer = Bitmap.createBitmap(
                            image.width, image.height, Bitmap.Config.ARGB_8888
                        )
                    }
                    processImage(image)
                })
            }

        try {
            cameraProvider.unbindAll()
            // Temporarily remove videoCapture for testing
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer // Bind only Preview and ImageAnalysis
                // videoCapture // Comment out VideoCapture
            )
            Log.d(TAG, "Camera use cases bound (Preview + ImageAnalysis only)") // Add log
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            toast("Failed to start camera.")
        }
    }

    private fun processImage(image: ImageProxy) {
        try {
            // Convert YUV ImageProxy to Bitmap
            converter.yuvToRgb(image, bitmapBuffer)

            // Rotate bitmap if needed based on image.imageInfo.rotationDegrees
            val rotatedBitmap = bitmapBuffer // Placeholder, add rotation if needed

            instanceSegmentation?.invoke(
                frame = rotatedBitmap,
                smoothEdges = viewModel.isSmoothEdges,
                onSuccess = { successResult ->
                    // Add logging here
                    Log.d(TAG, "onSuccess called. Pre: ${successResult.preProcessTime}, Inf: ${successResult.interfaceTime}, Post: ${successResult.postProcessTime}")
                    requireActivity().runOnUiThread {
                        // Log just before UI update
                        Log.d(TAG, "Updating UI on main thread.")
                        updateUIWithResults(successResult, rotatedBitmap.width, rotatedBitmap.height)
                    }
                },
                onFailure = { errorMsg ->
                    requireActivity().runOnUiThread {
                        overlayView.clear() // Clear overlay on failure
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
        } finally {
            image.close() // IMPORTANT: Close the ImageProxy
        }
    }

    private fun updateUIWithResults(success: Success, width: Int, height: Int) {
        binding.apply {
            tvPreProcess.text = getString(R.string.interface_time_value, success.preProcessTime.toString())
            tvInterfaceTime.text = getString(R.string.interface_time_value, success.interfaceTime.toString())
            tvPostProcess.text = getString(R.string.interface_time_value, success.postProcessTime.toString())
        }
        overlayView.setResults(success.results, width, height)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraExecutor.shutdown()
        instanceSegmentation?.close()
    }

    private fun showSettingsDialog() {
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

            cbSeparateOut.isEnabled = false
            cbMaskOut.isEnabled = false
            cbSeparateOut.alpha = 0.5f
            cbMaskOut.alpha = 0.5f

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
        dialog.show()
    }

    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "InstanceSegmentationFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}