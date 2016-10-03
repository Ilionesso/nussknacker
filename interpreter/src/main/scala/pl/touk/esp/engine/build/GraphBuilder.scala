package pl.touk.esp.engine.build

import pl.touk.esp.engine.graph.evaluatedparam.Parameter
import pl.touk.esp.engine.graph.expression._
import pl.touk.esp.engine.graph.node._
import pl.touk.esp.engine.graph.service.ServiceRef
import pl.touk.esp.engine.graph.sink.SinkRef
import pl.touk.esp.engine.graph.source.SourceRef
import pl.touk.esp.engine.graph.variable._
import pl.touk.esp.engine.graph.{param, service}

import scala.concurrent.duration.Duration

trait GraphBuilder[R] {

  def creator: GraphBuilder.Creator[R]

  protected def build(inner: GraphBuilder.Creator[R]): GraphBuilder[R]

  def buildVariable(id: String, varName: String, fields: (String, Expression)*) =
    build(node => creator(OneOutputSubsequentNode(VariableBuilder(id, varName, fields.map(Field.tupled).toList), node)))

  def processor(id: String, svcId: String, params: (String, Expression)*): GraphBuilder[R] =
    build(node => creator(OneOutputSubsequentNode(Processor(id, ServiceRef(svcId, params.map(Parameter.tupled).toList)), node)))

  def enricher(id: String, output: String, svcId: String, params: (String, Expression)*): GraphBuilder[R] =
    build(node => creator(OneOutputSubsequentNode(Enricher(id, ServiceRef(svcId, params.map(Parameter.tupled).toList), output), node)))

  def filter(id: String, expression: Expression): GraphBuilder[R] =
    build(node => creator(FilterNode(Filter(id, expression), node, None)))

  def filter(id: String, expression: Expression, nextFalse: SubsequentNode): GraphBuilder[R] =
    build(node => creator(FilterNode(Filter(id, expression), node, Some(nextFalse))))

  def aggregate(id: String, aggregatedVar: String,
                keyExpression: Expression, duration: Duration, step: Duration,
                triggerExpression: Option[Expression] = None, foldingFunRef: Option[String] = None): GraphBuilder[R] =
    build(node => creator(OneOutputSubsequentNode(Aggregate(id, aggregatedVar, keyExpression,
      duration.toMillis, step.toMillis, triggerExpression, foldingFunRef), node)))

  def sink(id: String, typ: String, params: (String, String)*): R =
    creator(EndingNode(Sink(id, SinkRef(typ, params.map(param.Parameter.tupled).toList))))

  def sink(id: String, expression: Expression, typ: String, params: (String, String)*): R =
    creator(EndingNode(Sink(id, SinkRef(typ, params.map(param.Parameter.tupled).toList), Some(expression))))

  def processorEnd(id: String, svcId: String, params: (String, Expression)*): R =
    creator(EndingNode(Processor(id, ServiceRef(svcId, params.map(Parameter.tupled).toList))))

  def switch(id: String, expression: Expression, exprVal: String, nexts: Case*): R =
    creator(SwitchNode(Switch(id, expression, exprVal), nexts.toList, None))

  def switch(id: String, expression: Expression, exprVal: String,
             defaultNext: SubsequentNode, nexts: Case*): R =
    creator(SwitchNode(Switch(id, expression, exprVal), nexts.toList, Some(defaultNext)))

  def customNode(id: String, outputVar: String, customNodeRef: String, params: (String, Expression)*): GraphBuilder[R]  =
    build(node => creator(OneOutputSubsequentNode(CustomNode(id, outputVar, customNodeRef, params.map(Parameter.tupled).toList), node)))

  def to(node: SubsequentNode): R =
    creator(node)

}

private[build] class SimpleGraphBuilder[R<:Node](val creator: GraphBuilder.Creator[R]) extends GraphBuilder[R] {
  override def build(inner: GraphBuilder.Creator[R]) = new SimpleGraphBuilder(inner)
}

object GraphBuilder extends GraphBuilder[SubsequentNode] {

  type Creator[R] = SubsequentNode => R

  override def creator = identity[SubsequentNode]

  override def build(inner: Creator[SubsequentNode]) = new SimpleGraphBuilder[SubsequentNode](inner)

  def source(id: String, typ: String, params: (String, String)*): GraphBuilder[SourceNode] =
    new SimpleGraphBuilder(SourceNode(Source(id, SourceRef(typ, params.map(param.Parameter.tupled).toList)), _))

}