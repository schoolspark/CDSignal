package `in`.chinmoydas.signal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import `in`.chinmoydas.signal.data.MainRepository

class ViewModelFactory(private val repository: MainRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WalkieViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WalkieViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}