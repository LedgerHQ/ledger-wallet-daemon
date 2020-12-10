package co.ledger.wallet.daemon.database

import java.nio.ByteBuffer
import java.util
import java.util.Base64

import co.ledger.core.{PreferencesBackend, PreferencesChange, PreferencesChangeType, RandomNumberGenerator}
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.redis.{RedisClientPool, RedisCommand}
import com.twitter.inject.Logging

import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.util.{Failure, Success, Try}

case class RedisClientConfiguration(
                                     poolName: String,
                                     host: String = "localhost",
                                     port: Int = 6379,
                                     password: Option[String] = None,
                                     db: Option[Int] = None,
                                     connectionTimeout: Option[Int] = Some(10)
                                   ) {
  val prefix: String = "core:user-preferences:" ++ poolName ++ ":"
}

class RedisPreferenceBackend(conf: RedisClientConfiguration) extends PreferencesBackend with Logging {
  type KeyPref = ByteBuffer
  type ValuePref = ByteBuffer
  val prefCache: LoadingCache[KeyPref, Option[ValuePref]] = CacheBuilder.newBuilder()
    .maximumSize(2000)
    .build[KeyPref, Option[ValuePref]](new CacheLoader[KeyPref, Option[ValuePref]] {
      override def load(key: KeyPref): Option[ValuePref] = {
        loadPrefEntry(key.array()).map[ValuePref](ByteBuffer.wrap)
      }
    })

  private val redisClients = new RedisClientPool(
    host = conf.host,
    port = conf.port,
    secret = conf.password,
    database = conf.db.getOrElse(0),
    timeout = conf.connectionTimeout.getOrElse(0),
    sslContext = None
  )

  def init(): Unit = {
  }

  override def get(bytes: Array[Byte]): Array[Byte] = {
    Try(prefCache.get(ByteBuffer.wrap(bytes)).map(_.array)) match {
      case Failure(exception) =>
        logger.error(s"Failed to load key ${bytes.mkString("Array(", ", ", ")")}", exception)
        Array.emptyByteArray
      case Success(value) => value.getOrElse(Array.emptyByteArray)
    }
  }

  /**
    * Encodes an array of bytes into a hex string prefixed with self.prefix
    *
    * Note: Since Redis is a full text protocol, hex strings is used to make sure no
    * weird characters text gets sent.
    */
  private def prepareKey(a: Array[Byte]): String = {
    conf.prefix ++ Base64.getEncoder.encodeToString(a)
  }

  private def loadPrefEntry(key: Array[Byte]): Option[Array[Byte]] = {
    redisClients.withClient { redis =>
      redis.get[String](prepareKey(key)).map(Base64.getDecoder.decode(_))
    }
  }

  override def commit(arrayList: util.ArrayList[PreferencesChange]): Boolean = {
    if (!arrayList.isEmpty) {
      val shouldBatch = arrayList.size() > 1
      val batchSize = 100

      redisClients.withClient { redis =>
        if (shouldBatch) {
          arrayList.asScala.grouped(batchSize)
            .foreach(prefBatch => {
              prefBatch.map(pref => prepareKey(pref.getKey))
                .foreach(redis.watch(_))
              redis.pipeline {
                applyPrefChangeToRedis(prefBatch, _)
              }
            })
        } else {
          applyPrefChangeToRedis(arrayList.asScala, redis)
        }
      }
      arrayList.forEach(p => prefCache.invalidate(ByteBuffer.wrap(p.getKey)))
      true
    } else true
  }

  def applyPrefChangeToRedis(prefChanges: Iterable[PreferencesChange], redis: RedisCommand): Unit = {
    prefChanges
      .filter(_.getType == PreferencesChangeType.PUT_TYPE)
      .foreach(preference => {
        val key = prepareKey(preference.getKey)

        redis.set(key, Base64.getEncoder.encodeToString(preference.getValue))
      })
    prefChanges
      .filter(_.getType == PreferencesChangeType.DELETE_TYPE)
      .foreach(preference => {
        val key = prepareKey(preference.getKey)

        redis.del(key)
      })
  }

  override def setEncryption(randomNumberGenerator: RandomNumberGenerator, s: String): Unit = Unit

  override def unsetEncryption(): Unit = Unit

  override def resetEncryption(randomNumberGenerator: RandomNumberGenerator, s: String, s1: String): Boolean = true

  override def getEncryptionSalt: String = ""

  override def clear(): Unit = {
    val batchSize = 1000
    var cursor: Option[Int] = None
    redisClients.withClient { redis =>
      do {
        val Some((maybeCursor, maybeData)) = redis.scan(cursor.getOrElse(0), conf.prefix ++ "*", count = batchSize)
        maybeData.foreach(maybeKeys => {
          redis.del(maybeKeys.flatten)
        })
        cursor = maybeCursor
      } while (cursor.isDefined)
    }
  }
}
