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

package com.bubelov.coins.api.coins

import com.bubelov.coins.model.Place
import com.bubelov.coins.model.User
import com.bubelov.coins.repository.place.PlacesAssetsCache
import retrofit2.Call
import retrofit2.mock.BehaviorDelegate
import java.util.*

class MockCoinsApi(
    private val delegate: BehaviorDelegate<CoinsApi>,
    assetsCache: PlacesAssetsCache
) : CoinsApi {
    private val places = mutableListOf<Place>()

    init {
        places.addAll(assetsCache.getPlaces())
    }

    override fun createUser(args: CreateUserArgs): Call<AuthResponse> {
        val response = AuthResponse(
            user = User(1L, args.user.email, args.user.firstName, args.user.lastName, ""),
            token = UUID.randomUUID().toString()
        )

        return delegate.returningResponse(response).createUser(args)
    }

    override fun authWithEmail(email: String, password: String): Call<AuthResponse> {
        val response = AuthResponse(
            user = User(1L, email, "Foo", "Bar", ""),
            token = UUID.randomUUID().toString()
        )

        return delegate.returningResponse(response).authWithEmail(email, password)
    }

    override fun authWithGoogle(token: String): Call<AuthResponse> {
        val response = AuthResponse(
            user = User(1L, "foo@bar.com", "Foo", "Bar", ""),
            token = UUID.randomUUID().toString()
        )

        return delegate.returningResponse(response).authWithGoogle(token)
    }

    override fun getPlaces(since: String, limit: Int): Call<List<Place>> {
        return delegate.returningResponse(emptyList<Place>()).getPlaces(since, limit)
    }

    override fun addPlace(session: String, args: AddPlaceArgs): Call<Place> {
        val place = args.place.copy(id = UUID.randomUUID().toString().hashCode().toLong())
        places += place
        return delegate.returningResponse(place).addPlace(session, args)
    }

    override fun updatePlace(id: Long, session: String, args: UpdatePlaceArgs): Call<Place> {
        val existingPlace = places.find { it.id == id }
        places.remove(existingPlace)
        places += args.place
        return delegate.returningResponse(args.place).updatePlace(id, session, args)
    }
}