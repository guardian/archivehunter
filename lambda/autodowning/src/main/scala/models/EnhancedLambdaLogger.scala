package models

import com.amazonaws.services.lambda.runtime.LambdaLogger

import java.io.{PrintWriter, StringWriter}

object EnhancedLambdaLogger {
  implicit class EnhancedException(exc:Throwable) {
    def formatStackTrace = {
      val s = new StringWriter()
      val w = new PrintWriter(s)
      exc.printStackTrace(w)
      s.toString
    }
  }

  implicit class EnhancedLambdaLogger(l:LambdaLogger) {
    def info(str:String) = l.log("INFO " + str)
    def info(str:String, exc:Throwable) = l.log("INFO " + str + " " + exc.getMessage + "\n" + exc.formatStackTrace)
    def warn(str:String) = l.log("WARNING " + str)
    def warn(str:String, exc:Throwable) = l.log("WARNING " + str + " " + exc.getMessage + "\n" + exc.formatStackTrace)
    def error(str:String) = l.log("ERROR " + str)
    def error(str:String, exc:Throwable) = l.log("ERROR " + str + " " + exc.getMessage + "\n" + exc.formatStackTrace)
    def debug(str:String) = l.log("DEBUG " + str)
    def debug(str:String, exc:Throwable) = l.log("DEBUG " + str + " " + exc.getMessage + "\n" + exc.formatStackTrace)
  }
}
