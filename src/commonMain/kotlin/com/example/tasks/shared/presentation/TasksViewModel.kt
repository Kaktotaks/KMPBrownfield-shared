package com.example.tasks.shared.presentation

import androidx.lifecycle.ViewModel

/**
 * Base for all shared ViewModels.
 *
 * On Android this is a regular androidx ViewModel: created by ViewModelProvider, survives
 * configuration changes, viewModelScope is cancelled in onCleared().
 * iOS has no ViewModelStoreOwner, so the Swift wrapper calls `clear()` (iosMain) when the screen
 * goes away; otherwise viewModelScope and its subscriptions to repository.state would leak.
 */
abstract class TasksViewModel : ViewModel()
