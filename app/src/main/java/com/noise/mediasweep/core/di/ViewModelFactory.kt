package com.noise.mediasweep.core.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.noise.mediasweep.MediaSweepApplication

/**
 * Resolves the [AppContainer] from [CreationExtras] so ViewModels can build their
 * factories without a DI framework.
 */
fun CreationExtras.appContainer(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MediaSweepApplication).container

/** Resolves the app container from a [android.content.Context] (used by routes directly). */
fun appContainer(context: android.content.Context): AppContainer =
    (context.applicationContext as MediaSweepApplication).container
