/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }
import java.util.concurrent.{ Executor, Executors }
import akka.event.LoggingAdapter

sealed trait DispatcherSelector
case object DispatcherDefault extends DispatcherSelector
final case class DispatcherFromConfig(path: String) extends DispatcherSelector
final case class DispatcherFromExecutor(executor: Executor) extends DispatcherSelector
final case class DispatcherFromExecutionContext(ec: ExecutionContext) extends DispatcherSelector

trait Dispatchers {
  def lookup(selector: DispatcherSelector): ExecutionContextExecutor
  def shutdown(): Unit
}
