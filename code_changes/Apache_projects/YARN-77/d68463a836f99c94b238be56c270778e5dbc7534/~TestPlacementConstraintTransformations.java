/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.api.resource;

import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.NODE;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.RACK;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.cardinality;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.maxCardinality;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.or;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.targetCardinality;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.targetIn;
import static org.apache.hadoop.yarn.api.resource.PlacementConstraints.PlacementTargets.allocationTag;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.yarn.api.resource.PlacementConstraint.AbstractConstraint;
import org.apache.hadoop.yarn.api.resource.PlacementConstraint.CardinalityConstraint;
import org.apache.hadoop.yarn.api.resource.PlacementConstraint.Or;
import org.apache.hadoop.yarn.api.resource.PlacementConstraint.SingleConstraint;
import org.apache.hadoop.yarn.api.resource.PlacementConstraint.TargetConstraint;
import org.apache.hadoop.yarn.api.resource.PlacementConstraint.TargetConstraint.TargetOperator;
import org.apache.hadoop.yarn.api.resource.PlacementConstraintTransformations.SingleConstraintTransformer;
import org.apache.hadoop.yarn.api.resource.PlacementConstraintTransformations.SpecializedConstraintTransformer;
import org.apache.hadoop.yarn.api.resource.PlacementConstraints.PlacementTargets;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test class for {@link PlacementConstraintTransformations}.
 */
public class TestPlacementConstraintTransformations {

  @Test
  public void testTargetConstraint() {
    AbstractConstraint sConstraintExpr =
        targetIn(NODE, allocationTag("hbase-m"));
    Assert.assertTrue(sConstraintExpr instanceof SingleConstraint);
    PlacementConstraint sConstraint =
        PlacementConstraints.build(sConstraintExpr);

    // Transform from SimpleConstraint to specialized TargetConstraint
    SpecializedConstraintTransformer specTransformer =
        new SpecializedConstraintTransformer(sConstraint);
    PlacementConstraint tConstraint = specTransformer.transform();

    AbstractConstraint tConstraintExpr = tConstraint.getConstraintExpr();
    Assert.assertTrue(tConstraintExpr instanceof TargetConstraint);

    SingleConstraint single = (SingleConstraint) sConstraintExpr;
    TargetConstraint target = (TargetConstraint) tConstraintExpr;
    Assert.assertEquals(single.getScope(), target.getScope());
    Assert.assertEquals(TargetOperator.IN, target.getOp());
    Assert.assertEquals(single.getTargetExpressions(),
        target.getTargetExpressions());

    // Transform from specialized TargetConstraint to SimpleConstraint
    SingleConstraintTransformer singleTransformer =
        new SingleConstraintTransformer(tConstraint);
    sConstraint = singleTransformer.transform();

    sConstraintExpr = sConstraint.getConstraintExpr();
    Assert.assertTrue(sConstraintExpr instanceof SingleConstraint);

    single = (SingleConstraint) sConstraintExpr;
    Assert.assertEquals(target.getScope(), single.getScope());
    Assert.assertEquals(1, single.getMinCardinality());
    Assert.assertEquals(Integer.MAX_VALUE, single.getMaxCardinality());
    Assert.assertEquals(single.getTargetExpressions(),
        target.getTargetExpressions());
  }

  @Test
  public void testCardinalityConstraint() {
    AbstractConstraint sConstraintExpr = cardinality(RACK, 3, 10);
    Assert.assertTrue(sConstraintExpr instanceof SingleConstraint);
    PlacementConstraint sConstraint =
        PlacementConstraints.build(sConstraintExpr);

    // Transform from SimpleConstraint to specialized CardinalityConstraint
    SpecializedConstraintTransformer specTransformer =
        new SpecializedConstraintTransformer(sConstraint);
    PlacementConstraint cConstraint = specTransformer.transform();

    AbstractConstraint cConstraintExpr = cConstraint.getConstraintExpr();
    Assert.assertTrue(cConstraintExpr instanceof CardinalityConstraint);

    SingleConstraint single = (SingleConstraint) sConstraintExpr;
    CardinalityConstraint cardinality = (CardinalityConstraint) cConstraintExpr;
    Assert.assertEquals(single.getScope(), cardinality.getScope());
    Assert.assertEquals(single.getMinCardinality(),
        cardinality.getMinCardinality());
    Assert.assertEquals(single.getMaxCardinality(),
        cardinality.getMaxCardinality());

    // Transform from specialized CardinalityConstraint to SimpleConstraint
    SingleConstraintTransformer singleTransformer =
        new SingleConstraintTransformer(cConstraint);
    sConstraint = singleTransformer.transform();

    sConstraintExpr = sConstraint.getConstraintExpr();
    Assert.assertTrue(sConstraintExpr instanceof SingleConstraint);

    single = (SingleConstraint) sConstraintExpr;
    Assert.assertEquals(cardinality.getScope(), single.getScope());
    Assert.assertEquals(cardinality.getMinCardinality(),
        single.getMinCardinality());
    Assert.assertEquals(cardinality.getMaxCardinality(),
        single.getMaxCardinality());
    Assert.assertEquals(new HashSet<>(Arrays.asList(PlacementTargets.self())),
        single.getTargetExpressions());
  }

  @Test
  public void testTargetCardinalityConstraint() {
    AbstractConstraint constraintExpr =
        targetCardinality(RACK, 3, 10, allocationTag("zk"));
    Assert.assertTrue(constraintExpr instanceof SingleConstraint);
    PlacementConstraint constraint = PlacementConstraints.build(constraintExpr);

    // Apply transformation. Should be a no-op.
    SpecializedConstraintTransformer specTransformer =
        new SpecializedConstraintTransformer(constraint);
    PlacementConstraint newConstraint = specTransformer.transform();

    // The constraint expression should be the same.
    Assert.assertEquals(constraintExpr, newConstraint.getConstraintExpr());
  }

  @Test
  public void testCompositeConstraint() {
    AbstractConstraint constraintExpr =
        or(targetIn(RACK, allocationTag("spark")), maxCardinality(NODE, 3),
            targetCardinality(RACK, 2, 10, allocationTag("zk")));
    Assert.assertTrue(constraintExpr instanceof Or);
    PlacementConstraint constraint = PlacementConstraints.build(constraintExpr);
    Or orExpr = (Or) constraintExpr;
    for (AbstractConstraint child : orExpr.getChildren()) {
      Assert.assertTrue(child instanceof SingleConstraint);
    }

    // Apply transformation. Should transform target and cardinality constraints
    // included in the composite constraint to specialized ones.
    SpecializedConstraintTransformer specTransformer =
        new SpecializedConstraintTransformer(constraint);
    PlacementConstraint specConstraint = specTransformer.transform();

    Or specOrExpr = (Or) specConstraint.getConstraintExpr();
    List<AbstractConstraint> specChildren = specOrExpr.getChildren();
    Assert.assertEquals(3, specChildren.size());
    Assert.assertTrue(specChildren.get(0) instanceof TargetConstraint);
    Assert.assertTrue(specChildren.get(1) instanceof CardinalityConstraint);
    Assert.assertTrue(specChildren.get(2) instanceof SingleConstraint);

    // Transform from specialized TargetConstraint to SimpleConstraint
    SingleConstraintTransformer singleTransformer =
        new SingleConstraintTransformer(specConstraint);
    PlacementConstraint simConstraint = singleTransformer.transform();
    Assert.assertTrue(constraintExpr instanceof Or);
    Or simOrExpr = (Or) specConstraint.getConstraintExpr();
    for (AbstractConstraint child : simOrExpr.getChildren()) {
      Assert.assertTrue(child instanceof SingleConstraint);
    }
  }

}
