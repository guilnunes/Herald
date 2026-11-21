package com.guilnunes.herald.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.guilnunes.herald.HeraldApp
import com.guilnunes.herald.data.PageRef
import com.guilnunes.herald.data.TextUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Screen { SETUP, HOME, READER }

data class SearchState(
    val query: String = "",
    val results: List<PageRef> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val herald = app as HeraldApp
    val repo = herald.repo
    val engine = herald.engine

    private val _screen = MutableStateFlow(if (repo.prefs.token == null) Screen.SETUP else Screen.HOME)
    val screen: StateFlow<Screen> = _screen

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private var searchJob: Job? = null

    init { if (repo.prefs.token != null) runSearch("", immediate = true) }

    fun go(s: Screen) { _screen.value = s }

    fun openPage(id: String) {
        engine.open(id)
        _screen.value = Screen.READER
    }

    fun handleShared(text: String): Boolean {
        val id = TextUtil.extractPageId(text) ?: return false
        if (repo.prefs.token == null) return false
        openPage(id)
        return true
    }

    fun onQuery(q: String) {
        _search.value = _search.value.copy(query = q)
        runSearch(q, immediate = false)
    }

    fun retrySearch() = runSearch(_search.value.query, immediate = true)

    private fun runSearch(q: String, immediate: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(450)
            _search.value = _search.value.copy(loading = true, error = null)
            try {
                val (pages, _) = repo.api.search(q.trim())
                _search.value = _search.value.copy(results = pages, loading = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _search.value = _search.value.copy(loading = false, error = e.message ?: "Search failed.")
            }
        }
    }

    fun saveToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) { _setupError.value = "Paste the token first."; return }
        val previous = repo.prefs.token
        repo.prefs.token = t
        _checking.value = true
        _setupError.value = null
        viewModelScope.launch {
            try {
                val (pages, _) = repo.api.search("")
                _checking.value = false
                _search.value = SearchState(results = pages)
                _setupError.value = if (pages.isEmpty()) "Token works, but no pages are connected yet. Do step 2, then search again." else null
                _screen.value = Screen.HOME
            } catch (e: Exception) {
                repo.prefs.token = previous
                _checking.value = false
                _setupError.value = e.message ?: "That token didn't work."
            }
        }
    }
}
