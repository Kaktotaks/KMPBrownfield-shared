package com.example.tasks.shared.presentation

import androidx.lifecycle.ViewModelStore

/**
 * iOS only: destroys the ViewModel explicitly.
 * On Android the ViewModelStore belongs to an Activity/NavBackStackEntry and is cleared for us.
 * iOS has no owner, so the Swift wrapper calls `viewModel.clear()` in deinit.
 * ViewModelStore.clear() calls the internal ViewModel.clear(): cancels viewModelScope (and with it
 * the stateIn/combine subscriptions to repository.state) and calls onCleared().
 * An extension on our class, so Swift sees a plain method: `viewModel.clear()`.
 */
fun TasksViewModel.clear() {
    val store = ViewModelStore()
    store.put("viewModel", this)
    store.clear()
}
