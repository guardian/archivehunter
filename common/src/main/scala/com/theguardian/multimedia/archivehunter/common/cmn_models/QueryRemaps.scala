package com.theguardian.multimedia.archivehunter.common.cmn_models

import org.scanamo.DynamoReadError

import scala.concurrent.{ExecutionContext, Future}

trait QueryRemaps {
  /**
    * convenience method to make it simpler to process dynamo responses by factoring out error-collection code into one play.
    * @param input a Dynamo response to process
    * @param ec implicitly provided execution context
    * @tparam A type of the data model held within the Dynamo response
    * @return a Future contianing either a single DynamoReadError or a list of responses.
    */
  def droppingConvert[A](input:Future[List[Either[DynamoReadError, A]]])(implicit ec:ExecutionContext):Future[Either[DynamoReadError, List[A]]] =
    input.map(results=>{
      val errors = results.collect({case Left(err)=>err})

      if(errors.nonEmpty){

        Left(errors.head)
      } else {
        Right(results.collect({case Right(entry)=>entry}))
      }
    })
}
