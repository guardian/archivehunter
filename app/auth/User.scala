/*
 * Copyright (C) 2015 Jason Mar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Andy Gallagher to provide cache instance via implicits
 */

package auth

import com.unboundid.ldap.sdk.LDAPConnectionPool
import play.api.cache.SyncCacheApi

import scala.util.{Failure, Success, Try}

case class User(uid: String, name: String)

object User {

  def authenticate(uid: String, pass: String)(implicit cache:SyncCacheApi): Try[Option[User]] = {
    LDAP.bind(uid,pass) match {
        case Success(0) => Success(Some(User(uid,LDAP.getFullName(uid))))
        case Success(_) => Success(None)
        case Failure(error)=>Failure(error)
    }
  }

}
