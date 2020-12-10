package co.ledger.wallet.daemon.database

import java.nio.ByteBuffer
import java.util

import co.ledger.core.{PreferencesBackend, PreferencesChange, PreferencesChangeType, RandomNumberGenerator}
import com.twitter.inject.Logging

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * DO NOT USE IN PRODUCTION !!
  */
class InMemoryPreferenceBackend() extends PreferencesBackend with Logging {
  type KeyPref = ByteBuffer
  type ValuePref = ByteBuffer

  private val preferences: mutable.Map[KeyPref, ValuePref] = mutable.Map[KeyPref, ValuePref]()

  override def get(bytes: Array[Byte]): Array[Byte] = synchronized {
    preferences.get(ByteBuffer.wrap(bytes)).map(_.array).orNull
  }

  override def commit(arrayList: util.ArrayList[PreferencesChange]): Boolean = synchronized {
    arrayList.asScala.foreach(prefChange =>
      if (prefChange.getType.equals(PreferencesChangeType.PUT_TYPE)) {
        preferences.put(ByteBuffer.wrap(prefChange.getKey), ByteBuffer.wrap(prefChange.getValue))
      } else {
        preferences.remove(ByteBuffer.wrap(prefChange.getKey))
      }
    )
    true
  }

  override def setEncryption(randomNumberGenerator: RandomNumberGenerator, s: String): Unit = Unit

  override def unsetEncryption(): Unit = Unit

  override def resetEncryption(randomNumberGenerator: RandomNumberGenerator, s: String, s1: String): Boolean = true

  override def getEncryptionSalt: String = ""

  override def clear(): Unit = preferences.clear()
}
