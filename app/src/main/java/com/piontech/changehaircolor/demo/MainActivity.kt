package com.piontech.changehaircolor.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.piontech.changehaircolor.demo.camera.CameraActivity
import com.piontech.changehaircolor.demo.databinding.ActivityMainBinding
import com.piontech.changehaircolor.demo.editor.HairEditorActivity
import com.piontech.changehaircolor.demo.util.ImmersiveActivity

/** Entry screen: pick a photo from the gallery, open the live camera, or try a sample. */
class MainActivity : ImmersiveActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            startActivity(HairEditorActivity.intentForUri(this, uri))
        }
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startActivity(Intent(this, CameraActivity::class.java))
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        binding.btnGallery.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }

        binding.btnCamera.setOnClickListener { openCamera() }

        setupSamples()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(this, CameraActivity::class.java))
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupSamples() {
        val samples = runCatching {
            assets.list("samples")?.sorted()?.map { "samples/$it" } ?: emptyList()
        }.getOrDefault(emptyList())

        binding.samplesRv.layoutManager = GridLayoutManager(this, 3)
        binding.samplesRv.adapter = SampleAdapter(samples) { assetPath ->
            startActivity(HairEditorActivity.intentForAsset(this, assetPath))
        }
    }
}
