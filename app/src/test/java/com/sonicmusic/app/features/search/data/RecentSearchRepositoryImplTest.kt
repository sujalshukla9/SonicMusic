package com.sonicmusic.app.features.search.data

import com.sonicmusic.app.data.local.dao.RecentSearchDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.local.entity.RecentSearchEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner::class)
class RecentSearchRepositoryImplTest {

    private class FakeRecentSearchDao : RecentSearchDao {
        val inserted = mutableListOf<RecentSearchEntity>()
        val deleted = mutableListOf<String>()
        var prunedLimit: Int? = null
        var cleared = false
        var emitList: List<RecentSearchEntity> = emptyList()

        override fun getRecentSearches(limit: Int): Flow<List<RecentSearchEntity>> {
            return flowOf(emitList.take(limit))
        }

        override suspend fun insertSearch(search: RecentSearchEntity) {
            inserted.add(search)
        }

        override suspend fun deleteSearch(query: String) {
            deleted.add(query)
        }

        override suspend fun clearAll() {
            cleared = true
        }

        override suspend fun pruneOldSearches(keepCount: Int) {
            prunedLimit = keepCount
        }
    }

    private lateinit var fakeDao: FakeRecentSearchDao

    @Mock
    private lateinit var settingsDataStore: SettingsDataStore

    private lateinit var repository: RecentSearchRepositoryImpl

    @Before
    fun setUp() {
        fakeDao = FakeRecentSearchDao()
        repository = RecentSearchRepositoryImpl(fakeDao, settingsDataStore)
    }

    @Test
    fun `addSearch does not save when query is blank or empty`() = runTest {
        repository.addSearch("   ")
        repository.addSearch("")

        assertTrue(fakeDao.inserted.isEmpty())
        assertTrue(fakeDao.deleted.isEmpty())
    }

    @Test
    fun `addSearch does not save when pauseHistory is enabled`() = runTest {
        whenever(settingsDataStore.pauseHistory).thenReturn(flowOf(true))

        repository.addSearch("Coldplay")

        assertTrue(fakeDao.inserted.isEmpty())
        assertTrue(fakeDao.deleted.isEmpty())
    }

    @Test
    fun `addSearch trims query and deletes prior duplicate before inserting`() = runTest {
        whenever(settingsDataStore.pauseHistory).thenReturn(flowOf(false))

        repository.addSearch("  Radiohead  ", resultId = "song_123", resultType = "SONG")

        assertEquals(listOf("Radiohead"), fakeDao.deleted)
        assertEquals(1, fakeDao.inserted.size)

        val saved = fakeDao.inserted.first()
        assertEquals("Radiohead", saved.query)
        assertEquals("song_123", saved.resultId)
        assertEquals("SONG", saved.resultType)
        assertTrue(saved.searchedAt > 0)
        assertEquals(20, fakeDao.prunedLimit)
    }

    @Test
    fun `getRecentSearches filters blanks and removes case-insensitive duplicates`() = runTest {
        fakeDao.emitList = listOf(
            RecentSearchEntity(query = "Queen", searchedAt = 3000L),
            RecentSearchEntity(query = "queen", searchedAt = 2000L),
            RecentSearchEntity(query = "   ", searchedAt = 1500L),
            RecentSearchEntity(query = "Daft Punk", searchedAt = 1000L)
        )

        val result = repository.getRecentSearches(10).first()

        assertEquals(2, result.size)
        assertEquals("Queen", result[0].query)
        assertEquals("Daft Punk", result[1].query)
    }

    @Test
    fun `deleteSearch trims query and calls dao delete`() = runTest {
        repository.deleteSearch("  Oasis  ")

        assertEquals(listOf("Oasis"), fakeDao.deleted)
    }

    @Test
    fun `deleteSearch ignores blank queries`() = runTest {
        repository.deleteSearch("   ")

        assertTrue(fakeDao.deleted.isEmpty())
    }

    @Test
    fun `clearAllSearches delegates to dao clearAll`() = runTest {
        assertFalse(fakeDao.cleared)
        repository.clearAllSearches()
        assertTrue(fakeDao.cleared)
    }
}
