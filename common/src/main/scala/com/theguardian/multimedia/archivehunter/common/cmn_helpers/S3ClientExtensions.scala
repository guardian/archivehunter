package com.theguardian.multimedia.archivehunter.common.cmn_helpers

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, HeadObjectRequest, NoSuchKeyException}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.{GetObjectPresignRequest, PresignedGetObjectRequest}

import java.time.Duration
import scala.util.{Failure, Success, Try}

object S3ClientExtensions {
  implicit class S3ClientExtensions(val client:S3Client) {
    /**
      * Returns `true` if the given object (with optional version) exists in the bucket or `false` otherwise.
      * @param bucket
      * @param path
      * @param maybeVersion
      * @return
      */
    def doesObjectExist(bucket:String, path:String, maybeVersion:Option[String]):Try[Boolean] = {
      getObjectMetadata(bucket, path, maybeVersion) match {
        case Success(_)=>Success(true)
        case Failure(ex:NoSuchKeyException)=>Success(false)
        case Failure(other)=>Failure(other)
      }
    }

    def doesObjectExist(bucket:String, path:String):Try[Boolean] = doesObjectExist(bucket, path, None)

    def generatePresignedUrl(bucket:String, key:String, expireInSeconds:Option[Int], region:Region) = Try {
      val presigner = S3Presigner.builder().region(region).build()
      val getReq = GetObjectRequest.builder().bucket(bucket).key(key).build()
      val preReq = GetObjectPresignRequest.builder().getObjectRequest(getReq)

      val finalReq = expireInSeconds match {
        case Some(expiry)=>
          preReq.signatureDuration(Duration.ofSeconds(expiry.toLong))
        case None=>
          preReq
      }
      presigner.presignGetObject(finalReq.build()).url()
    }

    def getObjectMetadata(bucket:String, path:String, maybeVersion:Option[String]) = {
      val initial = HeadObjectRequest.builder().bucket(bucket).key(path)
      val finalBuilder = maybeVersion match {
        case Some(ver)=>initial.versionId(ver)
        case None=>initial
      }

      Try {
        client.headObject(finalBuilder.build())
      }
    }
  }

}
