import com.amazonaws.services.lambda.runtime.LambdaLogger

class FakeLogger extends LambdaLogger {
  override def log(string: String): Unit = ()
}
