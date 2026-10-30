package com.example.tasks.shared.presentation

import androidx.lifecycle.ViewModelStore

/**
 * ⚡️ iOS-only: ручне знищення ViewModel.
 * На Android ViewModelStore належить Activity/NavBackStackEntry і чиститься сам.
 * На iOS власника немає, тому Swift-обгортка викликає `viewModel.clear()` у deinit.
 * ViewModelStore.clear() викликає внутрішній ViewModel.clear(): скасовує viewModelScope
 * (а з ним stateIn/combine-підписки на repository.state) і викликає onCleared().
 * Extension на наш клас → у Swift це звичайний метод: `viewModel.clear()`.
 */
fun TasksViewModel.clear() {
    val store = ViewModelStore()
    store.put("viewModel", this)
    store.clear()
}
