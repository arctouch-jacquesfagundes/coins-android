/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <https://unlicense.org>
 */

package com.bubelov.coins.db.sync

import com.bubelov.coins.model.Place
import com.bubelov.coins.model.SyncLogEntry
import com.bubelov.coins.repository.Result

import com.bubelov.coins.repository.place.PlacesRepository
import com.bubelov.coins.repository.synclogs.SyncLogsRepository
import com.bubelov.coins.util.PlaceNotificationManager

import javax.inject.Inject
import javax.inject.Singleton

import timber.log.Timber

@Singleton
class DatabaseSync @Inject
internal constructor(
    private val placesRepository: PlacesRepository,
    private val placeNotificationManager: PlaceNotificationManager,
    private val syncLogsRepository: SyncLogsRepository,
    private val databaseSyncScheduler: SyncScheduler
) {
    fun sync() {
        try {
            val result = placesRepository.fetchNewPlaces()

            when (result) {
                is Result.Success -> onFetchNewPlaces(result.data)
                is Result.Error -> throw result.e
            }
        } catch (e: Throwable) {
            Timber.e(e, "Couldn't sync database")
        }

        databaseSyncScheduler.scheduleNextSync()
    }

    private fun onFetchNewPlaces(places: Collection<Place>) {
        syncLogsRepository.insert(
            SyncLogEntry(
                System.currentTimeMillis(),
                places.size
            )
        )

        placeNotificationManager.issueNotificationsIfNecessary(places)
    }
}