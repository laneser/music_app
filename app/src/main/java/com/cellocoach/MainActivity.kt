package com.cellocoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import com.cellocoach.audio.AudioPitchSource
import com.cellocoach.core.PitchSource
import com.cellocoach.ui.CelloCoachApp
import com.cellocoach.ui.PracticeViewModel

/**
 * Single Activity host.
 *
 * Responsibilities:
 *  - Request the `RECORD_AUDIO` runtime permission up front (the
 *    [AudioPitchSource] assumes it's already granted).
 *  - Build the [PracticeViewModel] with an injected [PitchSource]. Production
 *    uses [AudioPitchSource]; the [PracticeViewModel.factory] indirection is what
 *    lets a Robolectric test construct the same ViewModel with a
 *    [com.cellocoach.audio.FakePitchSource] instead.
 *  - `setContent { CelloCoachApp(viewModel) }`.
 *
 * The whole audio/UI stack moved in-process here, so unlike the original Flask
 * project there is no HTTPS / secure-context / LAN plumbing — just a permission
 * prompt and Compose.
 */
class MainActivity : ComponentActivity() {

    /** Swappable for tests; defaults to the real mic-backed source. */
    private val pitchSource: PitchSource by lazy { AudioPitchSource() }

    private val viewModel: PracticeViewModel by viewModels {
        viewModelFactory()
    }

    /** Exposed so instrumentation/Robolectric can override the source if desired. */
    private fun viewModelFactory(): ViewModelProvider.Factory =
        PracticeViewModel.factory(application, pitchSource)

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not; source no-ops without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        setContent {
            CelloCoachApp(viewModel)
        }
    }
}
