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

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.analysis.EliminateSubQueries
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.LeftOuter
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.RightOuter
import org.apache.spark.sql.catalyst.plans.logical.ForeignKey
import org.apache.spark.sql.catalyst.plans.logical.KeyHint
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.plans.logical.UniqueKey
import org.apache.spark.sql.catalyst.rules.RuleExecutor

class JoinEliminationSuite extends PlanTest {
  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("Subqueries", FixedPoint(10), EliminateSubQueries) ::
      Batch("JoinElimination", Once, JoinElimination) :: Nil
  }

  val testRelation1 = LocalRelation('a.int, 'b.int)
  val testRelation2 = LocalRelation('c.int, 'd.int)
  val testRelation1K = KeyHint(List(UniqueKey(testRelation1.output.head)), testRelation1)
  val testRelation2K = KeyHint(List(UniqueKey(testRelation2.output.head)), testRelation2)
  val testRelation3 = LocalRelation('e.int, 'f.int)
  val testRelation3K = KeyHint(List(ForeignKey(testRelation3.output.head, testRelation1.output.head)), testRelation3)

  test("collapse left outer join followed by subset project") {
    val query = testRelation1
      .join(testRelation2K, LeftOuter, Some('a === 'c))
      .select('a, 'b)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation1.select('a, 'b).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("collapse right outer join followed by subset project") {
    val query = testRelation1K
      .join(testRelation2, RightOuter, Some('a === 'c))
      .select('c, 'd)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation2.select('c, 'd).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("collapse outer join followed by subset project with expressions") {
    val query = testRelation1
      .join(testRelation2K, LeftOuter, Some('a === 'c))
      .select(('a + 1).as('a), ('b + 2).as('b))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation1.select(('a + 1).as('a), ('b + 2).as('b)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("collapse outer join with foreign key") {
    val query = testRelation3K
      .join(testRelation1K, LeftOuter, Some('e === 'a))
      .select('a, 'f)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation3K.select('e.as('a), 'f).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("collapse outer join with foreign key despite alias") {
    val query = testRelation3K
      .join(testRelation1K.select('a.as('g), 'b), LeftOuter, Some('e === 'g))
      .select('g, 'f)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation3K.select('e.as('g), 'f).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("do not collapse non-subset project") {
    val query = testRelation1
      .join(testRelation2K, LeftOuter, Some('a === 'c))
      .select('a, 'b, ('c + 1).as('c), 'd)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = query.analyze

    comparePlans(optimized, correctAnswer)
  }

  test("do not collapse non-keyed join") {
    val query = testRelation1
      .join(testRelation2, LeftOuter, Some('a === 'c))
      .select('a, 'b, ('c + 1).as('c), 'd)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = query.analyze

    comparePlans(optimized, correctAnswer)
  }

  test("collapse join preceded by join") {
    val query1 = testRelation3K
      .join(testRelation1, LeftOuter, Some('e === 'a)) // will not be eliminated
    val query2 = query1
      .join(testRelation2K, LeftOuter, Some('a === 'c)) // should be eliminated
      .select('a, 'b, 'e, 'f)

    val optimized = Optimize.execute(query2.analyze)
    val correctAnswer = query1.select('a, 'b, 'e, 'f).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("eliminate inner join - fk on right, no pk columns kept") {
    val query = testRelation1K
      .join(testRelation3K, Inner, Some('a === 'e))
      .select('e, 'f)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation3K.select('e, 'f).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("eliminate inner join - fk on right, pk columns kept") {
    val query = testRelation1K
      .join(testRelation3K, Inner, Some('a === 'e))
      .select('a, 'f)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation3K.select('e.as('a), 'f).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("eliminate inner join - fk on left, pk columns kept") {
    val query = testRelation3K
      .join(testRelation1K, Inner, Some('a === 'e))
      .select('a, 'f)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation3K.select('e.as('a), 'f).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("triangles") {
    val e0 = LocalRelation('srcId.int, 'dstId.int)
    val v0 = LocalRelation('id.int, 'attr.int)
    val e = KeyHint(List(ForeignKey(e0.output.head, v0.output.head), ForeignKey(e0.output.last, v0.output.head)), e0)
    val v = KeyHint(List(UniqueKey(v0.output.head)), v0)

    val query = e.join(v, LeftOuter, Some('dstId === 'id)).select('srcId, 'id)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = e.select('srcId, 'dstId.as('id)).analyze

    comparePlans(optimized, correctAnswer)

  }
}
