package com.example.tasks.shared.presentation

import androidx.lifecycle.ViewModel

/**
 * Base for all shared ViewModels.
 *
 * ⚡️ На Android це звичайний androidx ViewModel: його створює ViewModelProvider,
 * він переживає поворот екрана, а viewModelScope скасовується в onCleared().
 * На iOS ViewModelStoreOwner немає — Swift-обгортка сама викликає `clear()` (iosMain),
 * коли екран зникає, інакше viewModelScope і підписки на repository.state житимуть вічно.
 */
abstract class TasksViewModel : ViewModel()
