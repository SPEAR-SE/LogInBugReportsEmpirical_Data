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

package org.apache.hadoop.yarn.server.timelineservice.storage.flow;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Bytes.ByteArrayComparator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.GenericConverter;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.NumericValueConverter;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineStorageUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ValueConverter;

/**
 * Invoked via the coprocessor when a Get or a Scan is issued for flow run
 * table. Looks through the list of cells per row, checks their tags and does
 * operation on those cells as per the cell tags. Transforms reads of the stored
 * metrics into calculated sums for each column Also, finds the min and max for
 * start and end times in a flow run.
 */
class FlowScanner implements RegionScanner, Closeable {

  private static final Log LOG = LogFactory.getLog(FlowScanner.class);

  private final HRegion region;
  private final InternalScanner flowRunScanner;
  private RegionScanner regionScanner;
  private final int limit;
  private boolean hasMore;
  private byte[] currentRow;
  private List<Cell> availableCells = new ArrayList<>();
  private int currentIndex;

  FlowScanner(HRegion region, int limit, InternalScanner internalScanner) {
    this.region = region;
    this.limit = limit;
    this.flowRunScanner = internalScanner;
    if (internalScanner instanceof RegionScanner) {
      this.regionScanner = (RegionScanner) internalScanner;
    }
    // TODO: note if it's compaction/flush
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hadoop.hbase.regionserver.RegionScanner#getRegionInfo()
   */
  @Override
  public HRegionInfo getRegionInfo() {
    return region.getRegionInfo();
  }

  @Override
  public boolean nextRaw(List<Cell> cells) throws IOException {
    return nextRaw(cells, limit);
  }

  @Override
  public boolean nextRaw(List<Cell> cells, int cellLimit) throws IOException {
    return nextInternal(cells, cellLimit);
  }

  @Override
  public boolean next(List<Cell> cells) throws IOException {
    return next(cells, limit);
  }

  @Override
  public boolean next(List<Cell> cells, int cellLimit) throws IOException {
    return nextInternal(cells, cellLimit);
  }

  private String getAggregationCompactionDimension(List<Tag> tags) {
    String appId = null;
    for (Tag t : tags) {
      if (AggregationCompactionDimension.APPLICATION_ID.getTagType() == t
          .getType()) {
        appId = Bytes.toString(t.getValue());
      }
    }
    return appId;
  }

  /**
   * Get value converter associated with a column or a column prefix. If nothing
   * matches, generic converter is returned.
   * @param colQualifierBytes
   * @return value converter implementation.
   */
  private static ValueConverter getValueConverter(byte[] colQualifierBytes) {
    // Iterate over all the column prefixes for flow run table and get the
    // appropriate converter for the column qualifier passed if prefix matches.
    for (FlowRunColumnPrefix colPrefix : FlowRunColumnPrefix.values()) {
      byte[] colPrefixBytes = colPrefix.getColumnPrefixBytes("");
      if (Bytes.compareTo(colPrefixBytes, 0, colPrefixBytes.length,
          colQualifierBytes, 0, colPrefixBytes.length) == 0) {
        return colPrefix.getValueConverter();
      }
    }
    // Iterate over all the columns for flow run table and get the
    // appropriate converter for the column qualifier passed if match occurs.
    for (FlowRunColumn column : FlowRunColumn.values()) {
      if (Bytes.compareTo(
          column.getColumnQualifierBytes(), colQualifierBytes) == 0) {
        return column.getValueConverter();
      }
    }
    // Return generic converter if nothing matches.
    return GenericConverter.getInstance();
  }

  /**
   * Checks if the converter is a numeric converter or not. For a converter to
   * be numeric, it must implement {@link NumericValueConverter} interface.
   * @param converter
   * @return true, if converter is of type NumericValueConverter, false
   * otherwise.
   */
  private static boolean isNumericConverter(ValueConverter converter) {
    return (converter instanceof NumericValueConverter);
  }

  /**
   * This method loops through the cells in a given row of the
   * {@link FlowRunTable}. It looks at the tags of each cell to figure out how
   * to process the contents. It then calculates the sum or min or max for each
   * column or returns the cell as is.
   *
   * @param cells
   * @param cellLimit
   * @return true if next row is available for the scanner, false otherwise
   * @throws IOException
   */
  private boolean nextInternal(List<Cell> cells, int cellLimit)
      throws IOException {
    Cell cell = null;
    startNext();
    // Loop through all the cells in this row
    // For min/max/metrics we do need to scan the entire set of cells to get the
    // right one
    // But with flush/compaction, the number of cells being scanned will go down
    // cells are grouped per column qualifier then sorted by cell timestamp
    // (latest to oldest) per column qualifier
    // So all cells in one qualifier come one after the other before we see the
    // next column qualifier
    ByteArrayComparator comp = new ByteArrayComparator();
    byte[] currentColumnQualifier = TimelineStorageUtils.EMPTY_BYTES;
    AggregationOperation currentAggOp = null;
    SortedSet<Cell> currentColumnCells = new TreeSet<>(KeyValue.COMPARATOR);
    Set<String> alreadySeenAggDim = new HashSet<>();
    int addedCnt = 0;
    ValueConverter converter = null;
    while (((cell = peekAtNextCell(cellLimit)) != null)
        && (cellLimit <= 0 || addedCnt < cellLimit)) {
      byte[] newColumnQualifier = CellUtil.cloneQualifier(cell);
      if (comp.compare(currentColumnQualifier, newColumnQualifier) != 0) {
        if (converter != null && isNumericConverter(converter)) {
          addedCnt += emitCells(cells, currentColumnCells, currentAggOp,
              (NumericValueConverter)converter);
        }
        resetState(currentColumnCells, alreadySeenAggDim);
        currentColumnQualifier = newColumnQualifier;
        currentAggOp = getCurrentAggOp(cell);
        converter = getValueConverter(newColumnQualifier);
      }
      // No operation needs to be performed on non numeric converters.
      if (!isNumericConverter(converter)) {
        nextCell(cellLimit);
        continue;
      }
      collectCells(currentColumnCells, currentAggOp, cell, alreadySeenAggDim,
          (NumericValueConverter)converter);
      nextCell(cellLimit);
    }
    if (!currentColumnCells.isEmpty()) {
      emitCells(cells, currentColumnCells, currentAggOp,
          (NumericValueConverter)converter);
    }
    return hasMore();
  }

  private AggregationOperation getCurrentAggOp(Cell cell) {
    List<Tag> tags = Tag.asList(cell.getTagsArray(), cell.getTagsOffset(),
        cell.getTagsLength());
    // We assume that all the operations for a particular column are the same
    return TimelineStorageUtils.getAggregationOperationFromTagsList(tags);
  }

  /**
   * resets the parameters to an intialized state for next loop iteration.
   *
   * @param cell
   * @param currentAggOp
   * @param currentColumnCells
   * @param alreadySeenAggDim
   * @param collectedButNotEmitted
   */
  private void resetState(SortedSet<Cell> currentColumnCells,
      Set<String> alreadySeenAggDim) {
    currentColumnCells.clear();
    alreadySeenAggDim.clear();
  }

  private void collectCells(SortedSet<Cell> currentColumnCells,
      AggregationOperation currentAggOp, Cell cell,
      Set<String> alreadySeenAggDim, NumericValueConverter converter)
      throws IOException {
    if (currentAggOp == null) {
      // not a min/max/metric cell, so just return it as is
      currentColumnCells.add(cell);
      nextCell(limit);
      return;
    }

    switch (currentAggOp) {
    case MIN:
      if (currentColumnCells.size() == 0) {
        currentColumnCells.add(cell);
      } else {
        Cell currentMinCell = currentColumnCells.first();
        Cell newMinCell = compareCellValues(currentMinCell, cell, currentAggOp,
            converter);
        if (!currentMinCell.equals(newMinCell)) {
          currentColumnCells.remove(currentMinCell);
          currentColumnCells.add(newMinCell);
        }
      }
      break;
    case MAX:
      if (currentColumnCells.size() == 0) {
        currentColumnCells.add(cell);
      } else {
        Cell currentMaxCell = currentColumnCells.first();
        Cell newMaxCell = compareCellValues(currentMaxCell, cell, currentAggOp,
            converter);
        if (!currentMaxCell.equals(newMaxCell)) {
          currentColumnCells.remove(currentMaxCell);
          currentColumnCells.add(newMaxCell);
        }
      }
      break;
    case SUM:
    case SUM_FINAL:
      // only if this app has not been seen yet, add to current column cells
      List<Tag> tags = Tag.asList(cell.getTagsArray(), cell.getTagsOffset(),
          cell.getTagsLength());
      String aggDim = getAggregationCompactionDimension(tags);

      // If this agg dimension has already been seen, since they show up in
      // sorted order, we drop the rest which are older. In other words, this
      // cell is older than previously seen cells for that agg dim.
      if (!alreadySeenAggDim.contains(aggDim)) {
        // Not seen this agg dim, hence consider this cell in our working set
        currentColumnCells.add(cell);
        alreadySeenAggDim.add(aggDim);
      }
      break;
    default:
      break;
    } // end of switch case
  }

  /*
   * Processes the cells in input param currentColumnCells and populates
   * List<Cell> cells as the output based on the input AggregationOperation
   * parameter.
   */
  private int emitCells(List<Cell> cells, SortedSet<Cell> currentColumnCells,
      AggregationOperation currentAggOp, NumericValueConverter converter)
      throws IOException {
    if ((currentColumnCells == null) || (currentColumnCells.size() == 0)) {
      return 0;
    }
    if (currentAggOp == null) {
      cells.addAll(currentColumnCells);
      return currentColumnCells.size();
    }

    switch (currentAggOp) {
    case MIN:
    case MAX:
      cells.addAll(currentColumnCells);
      return currentColumnCells.size();
    case SUM:
    case SUM_FINAL:
      Cell sumCell = processSummation(currentColumnCells, converter);
      cells.add(sumCell);
      return 1;
    default:
      cells.addAll(currentColumnCells);
      return currentColumnCells.size();
    }
  }

  /*
   * Returns a cell whose value is the sum of all cell values in the input set.
   * The new cell created has the timestamp of the most recent metric cell. The
   * sum of a metric for a flow run is the summation at the point of the last
   * metric update in that flow till that time.
   */
  private Cell processSummation(SortedSet<Cell> currentColumnCells,
      NumericValueConverter converter) throws IOException {
    Number sum = 0;
    Number currentValue = 0;
    long ts = 0L;
    long mostCurrentTimestamp = 0L;
    Cell mostRecentCell = null;
    for (Cell cell : currentColumnCells) {
      currentValue = (Number) converter.decodeValue(CellUtil.cloneValue(cell));
      ts = cell.getTimestamp();
      if (mostCurrentTimestamp < ts) {
        mostCurrentTimestamp = ts;
        mostRecentCell = cell;
      }
      sum = converter.add(sum, currentValue);
    }
    byte[] sumBytes = converter.encodeValue(sum);
    Cell sumCell = createNewCell(mostRecentCell, sumBytes);
    return sumCell;
  }

  /**
   * Determines which cell is to be returned based on the values in each cell
   * and the comparison operation MIN or MAX.
   *
   * @param previouslyChosenCell
   * @param currentCell
   * @param currentAggOp
   * @return the cell which is the min (or max) cell
   * @throws IOException
   */
  private Cell compareCellValues(Cell previouslyChosenCell, Cell currentCell,
      AggregationOperation currentAggOp, NumericValueConverter converter)
      throws IOException {
    if (previouslyChosenCell == null) {
      return currentCell;
    }
    try {
      Number previouslyChosenCellValue = (Number)converter.decodeValue(
          CellUtil.cloneValue(previouslyChosenCell));
      Number currentCellValue = (Number) converter.decodeValue(CellUtil
          .cloneValue(currentCell));
      switch (currentAggOp) {
      case MIN:
        if (converter.compare(
            currentCellValue, previouslyChosenCellValue) < 0) {
          // new value is minimum, hence return this cell
          return currentCell;
        } else {
          // previously chosen value is miniumum, hence return previous min cell
          return previouslyChosenCell;
        }
      case MAX:
        if (converter.compare(
            currentCellValue, previouslyChosenCellValue) > 0) {
          // new value is max, hence return this cell
          return currentCell;
        } else {
          // previously chosen value is max, hence return previous max cell
          return previouslyChosenCell;
        }
      default:
        return currentCell;
      }
    } catch (IllegalArgumentException iae) {
      LOG.error("caught iae during conversion to long ", iae);
      return currentCell;
    }
  }

  private Cell createNewCell(Cell origCell, byte[] newValue)
      throws IOException {
    return CellUtil.createCell(CellUtil.cloneRow(origCell),
        CellUtil.cloneFamily(origCell), CellUtil.cloneQualifier(origCell),
        origCell.getTimestamp(), KeyValue.Type.Put.getCode(), newValue);
  }

  @Override
  public void close() throws IOException {
    flowRunScanner.close();
  }

  /**
   * Called to signal the start of the next() call by the scanner.
   */
  public void startNext() {
    currentRow = null;
  }

  /**
   * Returns whether or not the underlying scanner has more rows.
   *
   * @return true, if there are more cells to return, false otherwise.
   */
  public boolean hasMore() {
    return currentIndex < availableCells.size() ? true : hasMore;
  }

  /**
   * Returns the next available cell for the current row and advances the
   * pointer to the next cell. This method can be called multiple times in a row
   * to advance through all the available cells.
   *
   * @param cellLimit
   *          the limit of number of cells to return if the next batch must be
   *          fetched by the wrapped scanner
   * @return the next available cell or null if no more cells are available for
   *         the current row
   * @throws IOException if any problem is encountered while grabbing the next
   *     cell.
   */
  public Cell nextCell(int cellLimit) throws IOException {
    Cell cell = peekAtNextCell(cellLimit);
    if (cell != null) {
      currentIndex++;
    }
    return cell;
  }

  /**
   * Returns the next available cell for the current row, without advancing the
   * pointer. Calling this method multiple times in a row will continue to
   * return the same cell.
   *
   * @param cellLimit
   *          the limit of number of cells to return if the next batch must be
   *          fetched by the wrapped scanner
   * @return the next available cell or null if no more cells are available for
   *         the current row
   * @throws IOException if any problem is encountered while grabbing the next
   *     cell.
   */
  public Cell peekAtNextCell(int cellLimit) throws IOException {
    if (currentIndex >= availableCells.size()) {
      // done with current batch
      availableCells.clear();
      currentIndex = 0;
      hasMore = flowRunScanner.next(availableCells, cellLimit);
    }
    Cell cell = null;
    if (currentIndex < availableCells.size()) {
      cell = availableCells.get(currentIndex);
      if (currentRow == null) {
        currentRow = CellUtil.cloneRow(cell);
      } else if (!CellUtil.matchingRow(cell, currentRow)) {
        // moved on to the next row
        // don't use the current cell
        // also signal no more cells for this row
        return null;
      }
    }
    return cell;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hadoop.hbase.regionserver.RegionScanner#getMaxResultSize()
   */
  @Override
  public long getMaxResultSize() {
    if (regionScanner == null) {
      throw new IllegalStateException(
          "RegionScanner.isFilterDone() called when the flow "
              + "scanner's scanner is not a RegionScanner");
    }
    return regionScanner.getMaxResultSize();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hadoop.hbase.regionserver.RegionScanner#getMvccReadPoint()
   */
  @Override
  public long getMvccReadPoint() {
    if (regionScanner == null) {
      throw new IllegalStateException(
          "RegionScanner.isFilterDone() called when the flow "
              + "scanner's internal scanner is not a RegionScanner");
    }
    return regionScanner.getMvccReadPoint();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hadoop.hbase.regionserver.RegionScanner#isFilterDone()
   */
  @Override
  public boolean isFilterDone() throws IOException {
    if (regionScanner == null) {
      throw new IllegalStateException(
          "RegionScanner.isFilterDone() called when the flow "
              + "scanner's internal scanner is not a RegionScanner");
    }
    return regionScanner.isFilterDone();

  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hadoop.hbase.regionserver.RegionScanner#reseek(byte[])
   */
  @Override
  public boolean reseek(byte[] bytes) throws IOException {
    if (regionScanner == null) {
      throw new IllegalStateException(
          "RegionScanner.reseek() called when the flow "
              + "scanner's internal scanner is not a RegionScanner");
    }
    return regionScanner.reseek(bytes);
  }
}
