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
package org.apache.hadoop.yarn.util.resource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.exceptions.ResourceNotFoundException;
import org.apache.hadoop.yarn.util.UnitsConversionUtil;


/**
 * A {@link ResourceCalculator} which uses the concept of
 * <em>dominant resource</em> to compare multi-dimensional resources.
 *
 * Essentially the idea is that the in a multi-resource environment,
 * the resource allocation should be determined by the dominant share
 * of an entity (user or queue), which is the maximum share that the
 * entity has been allocated of any resource.
 *
 * In a nutshell, it seeks to maximize the minimum dominant share across
 * all entities.
 *
 * For example, if user A runs CPU-heavy tasks and user B runs
 * memory-heavy tasks, it attempts to equalize CPU share of user A
 * with Memory-share of user B.
 *
 * In the single resource case, it reduces to max-min fairness for that resource.
 *
 * See the Dominant Resource Fairness paper for more details:
 * www.cs.berkeley.edu/~matei/papers/2011/nsdi_drf.pdf
 */
@Private
@Unstable
public class DominantResourceCalculator extends ResourceCalculator {
  private static final Log LOG =
      LogFactory.getLog(DominantResourceCalculator.class);


  private String[] resourceNames;

  public DominantResourceCalculator() {
    resourceNames = ResourceUtils.getResourceNamesArray();
  }

  /**
   * Compare two resources - if the value for every resource type for the lhs
   * is greater than that of the rhs, return 1. If the value for every resource
   * type in the lhs is less than the rhs, return -1. Otherwise, return 0
   *
   * @param lhs resource to be compared
   * @param rhs resource to be compared
   * @return 0, 1, or -1
   */
  private int compare(Resource lhs, Resource rhs) {
    boolean lhsGreater = false;
    boolean rhsGreater = false;
    int ret = 0;

    for (String rName : resourceNames) {
      try {
        ResourceInformation lhsResourceInformation =
            lhs.getResourceInformation(rName);
        ResourceInformation rhsResourceInformation =
            rhs.getResourceInformation(rName);
        int diff = lhsResourceInformation.compareTo(rhsResourceInformation);
        if (diff >= 1) {
          lhsGreater = true;
        } else if (diff <= -1) {
          rhsGreater = true;
        }
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + rName, ye);
      }
    }
    if (lhsGreater && rhsGreater) {
      ret = 0;
    } else if (lhsGreater) {
      ret = 1;
    } else if (rhsGreater) {
      ret = -1;
    }
    return ret;
  }

  @Override
  public int compare(Resource clusterResource, Resource lhs, Resource rhs,
      boolean singleType) {
    
    if (lhs.equals(rhs)) {
      return 0;
    }

    if (isInvalidDivisor(clusterResource)) {
      return this.compare(lhs, rhs);
    }

    float l = getResourceAsValue(clusterResource, lhs, true);
    float r = getResourceAsValue(clusterResource, rhs, true);

    if (l < r) {
      return -1;
    } else if (l > r) {
      return 1;
    } else if (!singleType) {
      l = getResourceAsValue(clusterResource, lhs, false);
      r = getResourceAsValue(clusterResource, rhs, false);

      if (l < r) {
        return -1;
      } else if (l > r) {
        return 1;
      }
    }

    return 0;
  }

  /**
   * Use 'dominant' for now since we only have 2 resources - gives us a slight
   * performance boost.
   * <p></p>
   * Once we add more resources, we'll need a more complicated (and slightly
   * less performant algorithm).
   */
  protected float getResourceAsValue(Resource clusterResource,
      Resource resource, boolean dominant) {

    float min = Float.MAX_VALUE;
    float max = 0.0f;
    for (String rName : resourceNames) {
      try {
        ResourceInformation clusterResourceResourceInformation =
            clusterResource.getResourceInformation(rName);
        ResourceInformation resourceInformation =
            resource.getResourceInformation(rName);
        long resourceValue = UnitsConversionUtil
            .convert(resourceInformation.getUnits(),
                clusterResourceResourceInformation.getUnits(),
                resourceInformation.getValue());
        float tmp =
            (float) resourceValue / (float) clusterResourceResourceInformation
                .getValue();
        min = min < tmp ? min : tmp;
        max = max > tmp ? max : tmp;
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }
    }
    return (dominant) ? max : min;
  }

  @Override
  public long computeAvailableContainers(Resource available, Resource required) {
    long min = Long.MAX_VALUE;
    for (String resource : resourceNames) {
      try {
        ResourceInformation availableResource =
            available.getResourceInformation(resource);
        ResourceInformation requiredResource =
            required.getResourceInformation(resource);
        long requiredResourceValue = UnitsConversionUtil
            .convert(requiredResource.getUnits(), availableResource.getUnits(),
                requiredResource.getValue());
        if (requiredResourceValue != 0) {
          long tmp = availableResource.getValue() / requiredResourceValue;
          min = min < tmp ? min : tmp;
        }
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }

    }
    return min > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) min;
  }

  @Override
  public float divide(Resource clusterResource,
      Resource numerator, Resource denominator) {
    return
        getResourceAsValue(clusterResource, numerator, true) /
        getResourceAsValue(clusterResource, denominator, true);
  }

  @Override
  public boolean isInvalidDivisor(Resource r) {
    for (ResourceInformation res : r.getResources()) {
      if (res.getValue() == 0L) {
        return true;
      }
    }
    return false;
  }

  @Override
  public float ratio(Resource a, Resource b) {
    float ratio = 0.0f;
    for (String resource : resourceNames) {
      try {
        ResourceInformation aResourceInformation =
            a.getResourceInformation(resource);
        ResourceInformation bResourceInformation =
            b.getResourceInformation(resource);
        long bResourceValue = UnitsConversionUtil
            .convert(bResourceInformation.getUnits(),
                aResourceInformation.getUnits(),
                bResourceInformation.getValue());
        float tmp =
            (float) aResourceInformation.getValue() / (float) bResourceValue;
        ratio = ratio > tmp ? ratio : tmp;
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }
    }
    return ratio;
  }

  @Override
  public Resource divideAndCeil(Resource numerator, int denominator) {
    return divideAndCeil(numerator, (long) denominator);
  }

  public Resource divideAndCeil(Resource numerator, long denominator) {
    Resource ret = Resource.newInstance(numerator);
    for (String resource : resourceNames) {
      try {
        ResourceInformation resourceInformation =
            ret.getResourceInformation(resource);
        resourceInformation.setValue(
            divideAndCeil(resourceInformation.getValue(), denominator));
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }
    }
    return ret;
  }

  @Override
  public Resource divideAndCeil(Resource numerator, float denominator) {
    return Resources.createResource(
        divideAndCeil(numerator.getMemorySize(), denominator),
        divideAndCeil(numerator.getVirtualCores(), denominator)
        );
  }

  @Override
  public Resource normalize(Resource r, Resource minimumResource,
      Resource maximumResource, Resource stepFactor) {
    Resource ret = Resource.newInstance(r);
    for (String resource : resourceNames) {
      try {
        ResourceInformation rResourceInformation =
            r.getResourceInformation(resource);
        ResourceInformation minimumResourceInformation =
            minimumResource.getResourceInformation(resource);
        ResourceInformation maximumResourceInformation =
            maximumResource.getResourceInformation(resource);
        ResourceInformation stepFactorResourceInformation =
            stepFactor.getResourceInformation(resource);
        ResourceInformation tmp = ret.getResourceInformation(resource);

        long rValue = rResourceInformation.getValue();
        long minimumValue = UnitsConversionUtil
            .convert(minimumResourceInformation.getUnits(),
                rResourceInformation.getUnits(),
                minimumResourceInformation.getValue());
        long maximumValue = UnitsConversionUtil
            .convert(maximumResourceInformation.getUnits(),
                rResourceInformation.getUnits(),
                maximumResourceInformation.getValue());
        long stepFactorValue = UnitsConversionUtil
            .convert(stepFactorResourceInformation.getUnits(),
                rResourceInformation.getUnits(),
                stepFactorResourceInformation.getValue());
        long value = Math.max(rValue, minimumValue);
        if (stepFactorValue != 0) {
          value = roundUp(value, stepFactorValue);
        }
        tmp.setValue(Math.min(value, maximumValue));
        ret.setResourceInformation(resource, tmp);
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }
    }
    return ret;
  }

  @Override
  public Resource roundUp(Resource r, Resource stepFactor) {
    return this.rounding(r, stepFactor, true);
  }

  @Override
  public Resource roundDown(Resource r, Resource stepFactor) {
    return this.rounding(r, stepFactor, false);
  }

  private Resource rounding(Resource r, Resource stepFactor, boolean roundUp) {
    Resource ret = Resource.newInstance(r);
    for (String resource : resourceNames) {
      try {
        ResourceInformation rResourceInformation =
            r.getResourceInformation(resource);
        ResourceInformation stepFactorResourceInformation =
            stepFactor.getResourceInformation(resource);

        long rValue = rResourceInformation.getValue();
        long stepFactorValue = UnitsConversionUtil
            .convert(stepFactorResourceInformation.getUnits(),
                rResourceInformation.getUnits(),
                stepFactorResourceInformation.getValue());
        long value = rValue;
        if (stepFactorValue != 0) {
          value = roundUp ? roundUp(rValue, stepFactorValue) :
              roundDown(rValue, stepFactorValue);
        }
        ResourceInformation
            .copy(rResourceInformation, ret.getResourceInformation(resource));
        ret.getResourceInformation(resource).setValue(value);
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }
    }
    return ret;
  }

  @Override
  public Resource multiplyAndNormalizeUp(Resource r, double by,
      Resource stepFactor) {
    return this.multiplyAndNormalize(r, by, stepFactor, true);
  }

  @Override
  public Resource multiplyAndNormalizeDown(Resource r, double by,
      Resource stepFactor) {
    return this.multiplyAndNormalize(r, by, stepFactor, false);
  }

  private Resource multiplyAndNormalize(Resource r, double by,
      Resource stepFactor, boolean roundUp) {
    Resource ret = Resource.newInstance(r);
    for (String resource : resourceNames) {
      try {
        ResourceInformation rResourceInformation = r
            .getResourceInformation(resource);
        ResourceInformation stepFactorResourceInformation = stepFactor
            .getResourceInformation(resource);
        ResourceInformation tmp = ret.getResourceInformation(resource);

        long rValue = rResourceInformation.getValue();
        long stepFactorValue = UnitsConversionUtil.convert(
            stepFactorResourceInformation.getUnits(),
            rResourceInformation.getUnits(),
            stepFactorResourceInformation.getValue());
        long value;
        if (stepFactorValue != 0) {
          value = roundUp
              ? roundUp((long) Math.ceil(rValue * by), stepFactorValue)
              : roundDown((long) (rValue * by), stepFactorValue);
        } else {
          value = roundUp
              ? (long) Math.ceil(rValue * by)
              : (long) (rValue * by);
        }
        tmp.setValue(value);
      } catch (ResourceNotFoundException ye) {
        throw new IllegalArgumentException(
            "Error getting resource information for " + resource, ye);
      }
    }
    return ret;
  }

  @Override
  public boolean fitsIn(Resource cluster, Resource smaller, Resource bigger) {
    for (String resource : resourceNames) {
      try {
        ResourceInformation sResourceInformation =
            smaller.getResourceInformation(resource);
        ResourceInformation bResourceInformation =
            bigger.getResourceInformation(resource);
        long sResourceValue = UnitsConversionUtil
            .convert(sResourceInformation.getUnits(),
                bResourceInformation.getUnits(),
                sResourceInformation.getValue());
        if(sResourceValue > bResourceInformation.getValue()) {
          return false;
        }
      } catch (ResourceNotFoundException ye) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean isAnyMajorResourceZero(Resource resource) {
    return resource.getMemorySize() == 0f || resource.getVirtualCores() == 0;
  }
}
