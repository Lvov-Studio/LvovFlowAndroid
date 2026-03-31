package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.Result
import com.king.zxing.CameraScan
import com.king.zxing.DefaultCameraScan
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.LogUtils
import com.king.zxing.util.PermissionUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutScannerBinding

/**
 * LvovFlow — QR Scanner for TV Pairing
 * Scans QR code and returns the result to DevicesActivity.
 * Uses the same zxing-lite camera API as the existing ScannerActivity.
 */
class TvQrScannerActivity : AppCompatActivity(), CameraScan.OnScanResultCallback {

    private lateinit var binding: LayoutScannerBinding
    private lateinit var cameraScan: CameraScan

    companion object {
        const val RESULT_QR_CONTENT = "qr_content"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide the toolbar (or use it for title)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
            title = "Сканировать QR"
        }

        // Init camera
        cameraScan = DefaultCameraScan(this, binding.previewView)
        cameraScan.setAnalyzer(QRCodeAnalyzer())
        cameraScan.setOnScanResultCallback(this)
        cameraScan.setNeedAutoZoom(true)

        // Start camera with permission check
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            PermissionUtils.requestPermission(this, Manifest.permission.CAMERA, 0x86)
        }

        // Flashlight toggle
        binding.ivFlashlight.setOnClickListener {
            val isTorch = cameraScan.isTorchEnabled
            cameraScan.enableTorch(!isTorch)
            binding.ivFlashlight.isSelected = !isTorch
        }
    }

    override fun onScanResultCallback(result: Result?): Boolean {
        val text = result?.text ?: return false

        // Return result to caller
        val resultIntent = Intent().apply {
            putExtra(RESULT_QR_CONTENT, text)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0x86) {
            if (PermissionUtils.requestPermissionsResult(
                    Manifest.permission.CAMERA, permissions, grantResults
                )
            ) {
                cameraScan.startCamera()
            } else {
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        cameraScan.release()
        super.onDestroy()
    }
}
