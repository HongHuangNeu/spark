/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions.Attribute

sealed abstract class Key {
  def transformAttribute(rule: PartialFunction[Attribute, Attribute]): Key
  def resolved: Boolean
}

case class UniqueKey(attr: Attribute) extends Key {
  override def transformAttribute(rule: PartialFunction[Attribute, Attribute]): Key =
    UniqueKey(rule.applyOrElse(attr, identity[Attribute]))

  override def resolved: Boolean = attr.resolved
}

/** Referenced column must be unique. Referenced relation must already be resolved. */
case class ForeignKey(
    attr: Attribute,
    referencedRelation: LogicalPlan,
    referencedAttr: Attribute) extends Key {
  assert(referencedRelation.resolved)

  override def transformAttribute(rule: PartialFunction[Attribute, Attribute]): Key =
    ForeignKey(rule.applyOrElse(attr, identity[Attribute]), referencedRelation, referencedAttr)

  override def resolved: Boolean = attr.resolved && referencedAttr.resolved
}
