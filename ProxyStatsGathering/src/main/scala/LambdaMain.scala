import java.util
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}

class LambdaMain extends RequestHandler[java.util.LinkedHashMap[String,Object],Unit] with MainContent {

  override def handleRequest(i: util.LinkedHashMap[String, Object], context: Context): Unit = {
    val indexer = getIndexer(getIndexName)


  }
}
