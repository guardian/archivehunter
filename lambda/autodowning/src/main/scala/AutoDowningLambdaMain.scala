import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import org.apache.logging.log4j.LogManager

class AutoDowningLambdaMain {
  private final val logger = LogManager.getLogger(getClass)

  def handleRequest(input:String, context:Context) = {
    println("Got input: ")
    println(input)
  }
}
