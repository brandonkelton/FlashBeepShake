package com.example.flashbeepshake

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    private var isLightOn = false
    private var isBeepOn = false
    private var isVibrateOn = false


    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupFunctions()
    }

    private fun setupFunctions() {
        button_light.setOnClickListener {
            isLightOn = !isLightOn
            button_light.text = if (isLightOn) resources.getString(R.string.button_lightOff) else resources.getString(R.string.button_lightOn)
            setLightStatus(isLightOn)
        }
        button_beep.setOnClickListener {
            isBeepOn = !isBeepOn
            button_beep.text = if (isBeepOn) resources.getString(R.string.button_beepOff) else resources.getString(R.string.button_beepOn)
            setBeepStatus(isBeepOn)
        }
        button_vibrate.setOnClickListener {
            isVibrateOn = !isVibrateOn
            button_vibrate.text = if (isVibrateOn) resources.getString(R.string.button_vibrateOff) else resources.getString(R.string.button_vibrateOn)
            setVibrateStatus(isVibrateOn)
        }
    }

    private fun setLightStatus(isLightOn: Boolean) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.setTorchMode(cameraId, isLightOn)
    }

    private fun setBeepStatus(isBeepOn: Boolean) {
        if (tone == null) {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        }
        if (isBeepOn) {
            tone!!.startTone(ToneGenerator.TONE_DTMF_S)
        } else {
            tone!!.stopTone()
        }
    }

    private fun setVibrateStatus(isVibrateOn: Boolean) {
        if (vibrator == null) {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (isVibrateOn) {
            if (Build.VERSION.SDK_INT >= 26) {
                val pattern = longArrayOf(0, 200, 100, 200, 100, 500, 200, 200, 100, 200, 100, 500, 200, 200, 100, 200, 100, 200, 100, 200, 100, 700, 500)
                val effect = VibrationEffect.createWaveform(pattern, 5)
                // vibrator!!.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                vibrator!!.vibrate(effect)
            } else {
                vibrator!!.vibrate(60000)
            }
        } else {
            vibrator!!.cancel()
        }
    }
}
