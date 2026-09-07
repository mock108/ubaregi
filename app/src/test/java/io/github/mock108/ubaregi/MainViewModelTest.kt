package io.github.mock108.ubaregi

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelTest {
    @Test
    fun startsAtHomeAndCanNavigateBackHome() {
        val viewModel = MainViewModel(SavedStateHandle())

        assertEquals("HOME", viewModel.route.value.name)
        viewModel.navigate(AppRoute.ABOUT)
        assertEquals("ABOUT", viewModel.route.value.name)
        viewModel.goHome()
        assertEquals("HOME", viewModel.route.value.name)
    }

    @Test
    fun restoresTheLastRouteFromSavedState() {
        val viewModel = MainViewModel(SavedStateHandle(mapOf("current_route" to "REGISTER")))

        assertEquals("REGISTER", viewModel.route.value.name)
    }
}
