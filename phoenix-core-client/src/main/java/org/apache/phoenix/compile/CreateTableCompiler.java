/*
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
package org.apache.phoenix.compile;

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_CHILD_LINK_NAMESPACE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_CHILD_LINK_NAME_BYTES;
import static org.apache.phoenix.query.QueryServices.DEFAULT_PHOENIX_UPDATABLE_VIEW_RESTRICTION_ENABLED;
import static org.apache.phoenix.query.QueryServices.PHOENIX_UPDATABLE_VIEW_RESTRICTION_ENABLED;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.expression.AndExpression;
import org.apache.phoenix.expression.ComparisonExpression;
import org.apache.phoenix.expression.Determinism;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.IsNullExpression;
import org.apache.phoenix.expression.KeyValueColumnExpression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.expression.RowKeyColumnExpression;
import org.apache.phoenix.expression.SingleCellColumnExpression;
import org.apache.phoenix.expression.visitor.StatelessTraverseNoExpressionVisitor;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.jdbc.PhoenixStatement.Operation;
import org.apache.phoenix.parse.BindParseNode;
import org.apache.phoenix.parse.ColumnDef;
import org.apache.phoenix.parse.ColumnParseNode;
import org.apache.phoenix.parse.CreateTableStatement;
import org.apache.phoenix.parse.ParseNode;
import org.apache.phoenix.parse.PrimaryKeyConstraint;
import org.apache.phoenix.parse.SQLParser;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.parse.TableName;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.ConnectionlessQueryServicesImpl;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.ColumnRef;
import org.apache.phoenix.schema.MetaDataClient;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PDatum;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.ViewType;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PVarbinary;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.ViewUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.collect.Iterators;

public class CreateTableCompiler {
  private static final Logger LOGGER = LoggerFactory.getLogger(CreateTableCompiler.class);
  private static final PDatum VARBINARY_DATUM = new VarbinaryDatum();
  private final PhoenixStatement statement;
  private final Operation operation;

  public CreateTableCompiler(PhoenixStatement statement, Operation operation) {
    this.statement = statement;
    this.operation = operation;
  }

  public MutationPlan compile(CreateTableStatement create) throws SQLException {
    final PhoenixConnection connection = statement.getConnection();
    ColumnResolver resolver = FromCompiler.getResolverForCreation(create, connection);
    PTableType type = create.getTableType();
    PTable parentToBe = null;
    ViewType viewTypeToBe = null;
    Scan scan = new Scan();
    final StatementContext context =
      new StatementContext(statement, resolver, scan, new SequenceManager(statement));
    // TODO: support any statement for a VIEW instead of just a WHERE clause
    ParseNode whereNode = create.getWhereClause();
    String viewStatementToBe = null;
    byte[][] viewColumnConstantsToBe = null;
    BitSet isViewColumnReferencedToBe = null;
    Set<PColumn> pkColumnsInWhere = new HashSet<>();
    Set<PColumn> nonPkColumnsInWhere = new HashSet<>();
    byte[] rowKeyMatcher = ByteUtil.EMPTY_BYTE_ARRAY;

    // Check whether column families having local index column family suffix or not if present
    // don't allow creating table.
    // Also validate the default values expressions.
    List<ColumnDef> columnDefs = create.getColumnDefs();
    List<ColumnDef> overideColumnDefs = null;
    PrimaryKeyConstraint pkConstraint = create.getPrimaryKeyConstraint();
    for (int i = 0; i < columnDefs.size(); i++) {
      ColumnDef columnDef = columnDefs.get(i);
      if (
        columnDef.getColumnDefName().getFamilyName() != null && columnDef.getColumnDefName()
          .getFamilyName().contains(QueryConstants.LOCAL_INDEX_COLUMN_FAMILY_PREFIX)
      ) {
        throw new SQLExceptionInfo.Builder(SQLExceptionCode.UNALLOWED_COLUMN_FAMILY).build()
          .buildException();
      }
      // False means we do not need the default (because it evaluated to null)
      if (!columnDef.validateDefault(context, pkConstraint)) {
        if (overideColumnDefs == null) {
          overideColumnDefs = new ArrayList<>(columnDefs);
        }
        overideColumnDefs.set(i, new ColumnDef(columnDef, null));
      }
    }
    if (overideColumnDefs != null) {
      create = new CreateTableStatement(create, overideColumnDefs);
    }
    final CreateTableStatement finalCreate = create;

    if (type == PTableType.VIEW) {
      TableRef tableRef = resolver.getTables().get(0);
      int nColumns = tableRef.getTable().getColumns().size();
      isViewColumnReferencedToBe = new BitSet(nColumns);
      // Used to track column references in a view
      ExpressionCompiler expressionCompiler =
        new ColumnTrackingExpressionCompiler(context, isViewColumnReferencedToBe);
      parentToBe = tableRef.getTable();

      // Disallow creating views on top of SYSTEM tables. See PHOENIX-5386
      if (parentToBe.getType() == PTableType.SYSTEM) {
        throw new SQLExceptionInfo.Builder(SQLExceptionCode.CANNOT_CREATE_VIEWS_ON_SYSTEM_TABLES)
          .build().buildException();
      }
      viewTypeToBe =
        parentToBe.getViewType() == ViewType.MAPPED ? ViewType.MAPPED : ViewType.UPDATABLE;
      Expression where = null;
      if (whereNode == null) {
        if (parentToBe.getViewType() == ViewType.READ_ONLY) {
          viewTypeToBe = ViewType.READ_ONLY;
        }
        viewStatementToBe = parentToBe.getViewStatement();
        if (viewStatementToBe != null) {
          SelectStatement select = new SQLParser(viewStatementToBe).parseQuery();
          whereNode = select.getWhere();
          where = whereNode.accept(expressionCompiler);
        }
      } else {
        whereNode = StatementNormalizer.normalize(whereNode, resolver);
        if (whereNode.isStateless()) {
          throw new SQLExceptionInfo.Builder(SQLExceptionCode.VIEW_WHERE_IS_CONSTANT).build()
            .buildException();
        }
        // If our parent has a VIEW statement, combine it with this one
        if (parentToBe.getViewStatement() != null) {
          SelectStatement select =
            new SQLParser(parentToBe.getViewStatement()).parseQuery().combine(whereNode);
          whereNode = select.getWhere();
        }
        where = whereNode.accept(expressionCompiler);
        if (where != null && !LiteralExpression.isTrue(where)) {
          TableName baseTableName = create.getBaseTableName();
          StringBuilder buf = new StringBuilder();
          whereNode.toSQL(resolver, buf);
          viewStatementToBe = QueryUtil.getViewStatement(baseTableName.getSchemaName(),
            baseTableName.getTableName(), buf.toString());
        }
        if (viewTypeToBe != ViewType.MAPPED) {
          viewColumnConstantsToBe = new byte[nColumns][];
          ViewWhereExpressionVisitor visitor =
            new ViewWhereExpressionVisitor(parentToBe, viewColumnConstantsToBe);
          where.accept(visitor);

          viewTypeToBe = visitor.isUpdatable() ? ViewType.UPDATABLE : ViewType.READ_ONLY;
          boolean updatableViewRestrictionEnabled = connection.getQueryServices().getProps()
            .getBoolean(PHOENIX_UPDATABLE_VIEW_RESTRICTION_ENABLED,
              DEFAULT_PHOENIX_UPDATABLE_VIEW_RESTRICTION_ENABLED);
          if (viewTypeToBe == ViewType.UPDATABLE && updatableViewRestrictionEnabled) {
            ViewWhereExpressionValidatorVisitor validatorVisitor =
              new ViewWhereExpressionValidatorVisitor(parentToBe, pkColumnsInWhere,
                nonPkColumnsInWhere);
            where.accept(validatorVisitor);
            if (!(connection.getQueryServices() instanceof ConnectionlessQueryServicesImpl)) {
              try {
                viewTypeToBe =
                  setViewTypeToBe(connection, parentToBe, pkColumnsInWhere, nonPkColumnsInWhere);
                LOGGER.info(
                  "VIEW type is set to {}. View Statement: {}, " + "View Name: {}, "
                    + "Parent Table/View Name: {}",
                  viewTypeToBe, viewStatementToBe, create.getTableName(), parentToBe.getName());
              } catch (IOException e) {
                throw new SQLException(e);
              }
            }
          }

          // If view is not updatable, viewColumnConstants should be empty. We will still
          // inherit our parent viewConstants, but we have no additional ones.
          if (viewTypeToBe != ViewType.UPDATABLE) {
            viewColumnConstantsToBe = null;
          }
        }
      }
      if (viewTypeToBe == ViewType.MAPPED && parentToBe.getPKColumns().isEmpty()) {
        validateCreateViewCompilation(connection, parentToBe, columnDefs, pkConstraint);
      } else if (where != null && viewTypeToBe == ViewType.UPDATABLE) {
        rowKeyMatcher =
          WhereOptimizer.getRowKeyMatcher(context, create.getTableName(), parentToBe, where);
      }
      verifyIfAnyParentHasIndexesAndViewExtendsPk(parentToBe, columnDefs, pkConstraint);
    }
    final ViewType viewType = viewTypeToBe;
    final String viewStatement = viewStatementToBe;
    final byte[][] viewColumnConstants = viewColumnConstantsToBe;
    final BitSet isViewColumnReferenced = isViewColumnReferencedToBe;
    List<ParseNode> splitNodes = create.getSplitNodes();
    final byte[][] splits = new byte[splitNodes.size()][];
    ImmutableBytesWritable ptr = context.getTempPtr();
    ExpressionCompiler expressionCompiler = new ExpressionCompiler(context);
    for (int i = 0; i < splits.length; i++) {
      ParseNode node = splitNodes.get(i);
      if (node instanceof BindParseNode) {
        context.getBindManager().addParamMetaData((BindParseNode) node, VARBINARY_DATUM);
      }
      if (node.isStateless()) {
        Expression expression = node.accept(expressionCompiler);
        if (expression.evaluate(null, ptr)) {
          ;
          splits[i] = ByteUtil.copyKeyBytesIfNecessary(ptr);
          continue;
        }
      }
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.SPLIT_POINT_NOT_CONSTANT)
        .setMessage("Node: " + node).build().buildException();
    }
    final MetaDataClient client = new MetaDataClient(connection);
    final PTable parent = parentToBe;

    return new CreateTableMutationPlan(context, client, finalCreate, splits, parent, viewStatement,
      viewType, rowKeyMatcher, viewColumnConstants, isViewColumnReferenced, connection);
  }

  /**
   * Restrict view to be UPDATABLE if the view specification: 1. uses only the PK columns; 2. starts
   * from the first PK column (ignore the prefix PK columns, TENANT_ID and/or _SALTED, if the parent
   * table is multi-tenant and/or salted); 3. PK columns should be in the order they are defined; 4.
   * uses the same set of PK columns as its sibling views' specification; Otherwise, mark the view
   * as READ_ONLY.
   * @param connection          The client connection
   * @param parentToBe          To be parent for given view
   * @param pkColumnsInWhere    Set of primary key in where clause
   * @param nonPkColumnsInWhere Set of non-primary key columns in where clause
   * @throws IOException thrown if there is an error finding sibling views
   */
  private ViewType setViewTypeToBe(final PhoenixConnection connection, final PTable parentToBe,
    final Set<PColumn> pkColumnsInWhere, final Set<PColumn> nonPkColumnsInWhere)
    throws IOException, SQLException {
    // 1. Check the view specification WHERE clause uses only the PK columns
    if (!nonPkColumnsInWhere.isEmpty()) {
      LOGGER.info("Setting the view type as READ_ONLY because the view statement contains "
        + "non-PK columns: {}", nonPkColumnsInWhere);
      return ViewType.READ_ONLY;
    }
    if (pkColumnsInWhere.isEmpty()) {
      return ViewType.UPDATABLE;
    }

    // 2. Check the WHERE clause starts from the first PK column (ignore the prefix PK
    // columns, TENANT_ID and/or _SALTED, if the parent table is multi-tenant and/or salted)
    List<Integer> tablePkPositions = new ArrayList<>();
    List<Integer> viewPkPositions = new ArrayList<>();
    List<PColumn> tablePkColumns = parentToBe.getPKColumns();
    tablePkColumns.forEach(tablePkColumn -> tablePkPositions.add(tablePkColumn.getPosition()));
    pkColumnsInWhere.forEach(pkColumn -> viewPkPositions.add(pkColumn.getPosition()));
    Collections.sort(viewPkPositions);
    int tablePkStartIdx = 0;
    if (parentToBe.isMultiTenant()) {
      tablePkStartIdx++;
    }
    if (parentToBe.getBucketNum() != null) {
      tablePkStartIdx++;
    }
    if (!Objects.equals(viewPkPositions.get(0), tablePkPositions.get(tablePkStartIdx))) {
      LOGGER.info("Setting the view type as READ_ONLY because the view statement WHERE "
        + "clause does not start from the first PK column (ignore the prefix PKs "
        + "if the parent table is multi-tenant and/or salted). View PK Columns: "
        + "{}, Table PK Columns: {}", pkColumnsInWhere, tablePkColumns);
      return ViewType.READ_ONLY;
    }

    // 3. Check PK columns are in the order they are defined
    if (!isPkColumnsInOrder(viewPkPositions, tablePkPositions, tablePkStartIdx)) {
      LOGGER.info(
        "Setting the view type as READ_ONLY because the PK columns is not in the "
          + "order they are defined. View PK Columns: {}, Table PK Columns: {}",
        pkColumnsInWhere, tablePkColumns);
      return ViewType.READ_ONLY;
    }

    // 4. Check the view specification has the same set of PK column(s) as its sibling view
    byte[] parentTenantIdInBytes =
      parentToBe.getTenantId() != null ? parentToBe.getTenantId().getBytes() : null;
    byte[] parentSchemaNameInBytes =
      parentToBe.getSchemaName() != null ? parentToBe.getSchemaName().getBytes() : null;
    ConnectionQueryServices queryServices = connection.getQueryServices();
    Configuration config = queryServices.getConfiguration();
    byte[] systemChildLinkTable = SchemaUtil.isNamespaceMappingEnabled(null, config)
      ? SYSTEM_CHILD_LINK_NAMESPACE_BYTES
      : SYSTEM_CHILD_LINK_NAME_BYTES;
    try (Table childLinkTable = queryServices.getTable(systemChildLinkTable)) {
      List<PTable> legitimateSiblingViewList = ViewUtil.findAllDescendantViews(childLinkTable,
        config, parentTenantIdInBytes, parentSchemaNameInBytes,
        parentToBe.getTableName().getBytes(), HConstants.LATEST_TIMESTAMP, true, false).getFirst();
      if (!legitimateSiblingViewList.isEmpty()) {
        PTable siblingView = legitimateSiblingViewList.get(0);
        Expression siblingViewWhere = getWhereFromView(connection, siblingView);
        Set<PColumn> siblingViewPkColsInWhere = new HashSet<>();
        if (siblingViewWhere != null) {
          ViewWhereExpressionValidatorVisitor siblingViewValidatorVisitor =
            new ViewWhereExpressionValidatorVisitor(parentToBe, siblingViewPkColsInWhere, null);
          siblingViewWhere.accept(siblingViewValidatorVisitor);
        }
        if (!pkColumnsInWhere.equals(siblingViewPkColsInWhere)) {
          LOGGER.info(
            "Setting the view type as READ_ONLY because its set of PK "
              + "columns is different from its sibling view {}'s. View PK "
              + "Columns: {}, Sibling View PK Columns: {}",
            siblingView.getName(), pkColumnsInWhere, siblingViewPkColsInWhere);
          return ViewType.READ_ONLY;
        }
      }
    }
    return ViewType.UPDATABLE;
  }

  /**
   * Get the where Expression of given view.
   * @param connection The client connection
   * @param view       PTable of the view
   * @return A where Expression
   */
  private Expression getWhereFromView(final PhoenixConnection connection, final PTable view)
    throws SQLException {
    String viewStatement = view.getViewStatement();
    if (viewStatement == null) {
      return null;
    }
    SelectStatement select = new SQLParser(viewStatement).parseQuery();
    ColumnResolver resolver = FromCompiler.getResolverForQuery(select, connection);
    StatementContext context = new StatementContext(new PhoenixStatement(connection), resolver);
    BitSet isViewColumnReferencedToBe = new BitSet(view.getColumns().size());
    ExpressionCompiler expressionCompiler =
      new ColumnTrackingExpressionCompiler(context, isViewColumnReferencedToBe);
    ParseNode whereNode = select.getWhere();
    return whereNode.accept(expressionCompiler);
  }

  /**
   * Check if the primary key columns are in order (consecutive in position) as they are defined,
   * providing their positions list
   * @param viewPkPositions  A positions list of view PK columns to be checked
   * @param tablePkPositions The positions list of the table's PK columns to be compared
   * @param tablePkStartIdx  The start index of table PK position, depending on whether the table is
   *                         multi-tenant and/or salted
   * @return true if the PK columns are in order, otherwise false
   */
  private boolean isPkColumnsInOrder(final List<Integer> viewPkPositions,
    final List<Integer> tablePkPositions, final int tablePkStartIdx) {
    for (int i = 1; i < viewPkPositions.size(); i++) {
      if (!Objects.equals(viewPkPositions.get(i), tablePkPositions.get(tablePkStartIdx + i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * If any of the parent table/view has indexes in the parent hierarchy, and the current view under
   * creation extends the primary key of the parent, throw error.
   * @param parentToBe   parent table/view of the current view under creation.
   * @param columnDefs   list of column definitions.
   * @param pkConstraint primary key constraint.
   * @throws SQLException if the view extends primary key and one of the parent view/table has
   *                      indexes in the parent hierarchy.
   */
  private void verifyIfAnyParentHasIndexesAndViewExtendsPk(PTable parentToBe,
    List<ColumnDef> columnDefs, PrimaryKeyConstraint pkConstraint) throws SQLException {
    if (viewExtendsParentPk(columnDefs, pkConstraint)) {
      PTable table = parentToBe;
      while (table != null) {
        if (table.getIndexes().size() > 0) {
          throw new SQLExceptionInfo.Builder(
            SQLExceptionCode.VIEW_CANNOT_EXTEND_PK_WITH_PARENT_INDEXES).build().buildException();
        }
        if (table.getType() != PTableType.VIEW) {
          return;
        }
        String schemaName = table.getParentSchemaName().getString();
        String tableName = table.getParentTableName().getString();
        try {
          table =
            statement.getConnection().getTable(SchemaUtil.getTableName(schemaName, tableName));
        } catch (TableNotFoundException e) {
          table = null;
        }
      }
    }
  }

  /**
   * Validate View creation compilation. 1. If view creation syntax does not specify primary key,
   * the method throws SQLException with PRIMARY_KEY_MISSING code. 2. If parent table does not
   * exist, the method throws TNFE.
   * @param connection   The client connection
   * @param parentToBe   To be parent for given view
   * @param columnDefs   List of column defs
   * @param pkConstraint PrimaryKey constraint retrieved from CreateTable statement
   * @throws SQLException If view creation validation fails
   */
  private void validateCreateViewCompilation(final PhoenixConnection connection,
    final PTable parentToBe, final List<ColumnDef> columnDefs,
    final PrimaryKeyConstraint pkConstraint) throws SQLException {
    boolean isPKMissed = true;
    if (pkConstraint.getColumnNames().size() > 0) {
      isPKMissed = false;
    } else {
      for (ColumnDef columnDef : columnDefs) {
        if (columnDef.isPK()) {
          isPKMissed = false;
          break;
        }
      }
    }
    PName fullTableName = SchemaUtil.getPhysicalHBaseTableName(parentToBe.getSchemaName(),
      parentToBe.getTableName(), parentToBe.isNamespaceMapped());
    // getTableIfExists will throw TNFE if table does not exist
    try (Table ignored = connection.getQueryServices().getTableIfExists(fullTableName.getBytes())) {
      // empty try block
    } catch (IOException e) {
      throw new SQLException(e);
    }
    if (isPKMissed) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.PRIMARY_KEY_MISSING).build()
        .buildException();
    }
  }

  /**
   * Returns true if the view extends the primary key of the parent table/view, returns false
   * otherwise.
   * @param columnDefs   column def list.
   * @param pkConstraint primary key constraint.
   * @return true if the view extends the primary key of the parent table/view, false otherwise.
   */
  private boolean viewExtendsParentPk(final List<ColumnDef> columnDefs,
    final PrimaryKeyConstraint pkConstraint) {
    if (pkConstraint.getColumnNames().size() > 0) {
      return true;
    } else {
      return columnDefs.stream().anyMatch(ColumnDef::isPK);
    }
  }

  public static class ColumnTrackingExpressionCompiler extends ExpressionCompiler {
    private final BitSet isColumnReferenced;

    public ColumnTrackingExpressionCompiler(StatementContext context, BitSet isColumnReferenced) {
      super(context, true);
      this.isColumnReferenced = isColumnReferenced;
    }

    @Override
    protected ColumnRef resolveColumn(ColumnParseNode node) throws SQLException {
      ColumnRef ref = super.resolveColumn(node);
      isColumnReferenced.set(ref.getColumn().getPosition());
      return ref;
    }
  }

  public static class ViewWhereExpressionVisitor
    extends StatelessTraverseNoExpressionVisitor<Boolean> {
    private boolean isUpdatable = true;
    private final PTable table;
    private int position;
    private final byte[][] columnValues;
    private final ImmutableBytesWritable ptr = new ImmutableBytesWritable();

    public ViewWhereExpressionVisitor(PTable table, byte[][] columnValues) {
      this.table = table;
      this.columnValues = columnValues;
    }

    public boolean isUpdatable() {
      return isUpdatable;
    }

    @Override
    public Boolean defaultReturn(Expression node, List<Boolean> l) {
      // We only hit this if we're trying to traverse somewhere
      // in which we don't have a visitLeave that returns non null
      isUpdatable = false;
      return null;
    }

    @Override
    public Iterator<Expression> visitEnter(AndExpression node) {
      return node.getChildren().iterator();
    }

    @Override
    public Boolean visitLeave(AndExpression node, List<Boolean> l) {
      return l.isEmpty() ? null : Boolean.TRUE;
    }

    @Override
    public Iterator<Expression> visitEnter(ComparisonExpression node) {
      if (
        node.getFilterOp() == CompareOperator.EQUAL && node.getChildren().get(1).isStateless()
          && node.getChildren().get(1).getDeterminism() == Determinism.ALWAYS
      ) {
        return Iterators.singletonIterator(node.getChildren().get(0));
      }
      return super.visitEnter(node);
    }

    @Override
    public Boolean visitLeave(ComparisonExpression node, List<Boolean> l) {
      if (l.isEmpty()) {
        return null;
      }

      node.getChildren().get(1).evaluate(null, ptr);
      // Set the columnValue at the position of the column to the
      // constant with which it is being compared.
      // We always strip the last byte so that we can recognize null
      // as a value with a single byte.
      columnValues[position] = new byte[ptr.getLength() + 1];
      System.arraycopy(ptr.get(), ptr.getOffset(), columnValues[position], 0, ptr.getLength());
      return Boolean.TRUE;
    }

    @Override
    public Iterator<Expression> visitEnter(IsNullExpression node) {
      return node.isNegate() ? super.visitEnter(node) : node.getChildren().iterator();
    }

    @Override
    public Boolean visitLeave(IsNullExpression node, List<Boolean> l) {
      // Nothing to do as we've already set the position to an empty byte array
      return l.isEmpty() ? null : Boolean.TRUE;
    }

    @Override
    public Boolean visit(RowKeyColumnExpression node) {
      this.position = table.getPKColumns().get(node.getPosition()).getPosition();
      return Boolean.TRUE;
    }

    @Override
    public Boolean visit(KeyValueColumnExpression node) {
      try {
        this.position = table.getColumnFamily(node.getColumnFamily())
          .getPColumnForColumnQualifier(node.getColumnQualifier()).getPosition();
      } catch (SQLException e) {
        throw new RuntimeException(e); // Impossible
      }
      return Boolean.TRUE;
    }

    @Override
    public Boolean visit(SingleCellColumnExpression node) {
      return visit(node.getKeyValueExpression());
    }

  }

  /**
   * Visitor for view's where expression, which updates primary key columns and non-primary key
   * columns for validating if the view is updatable
   */
  public static class ViewWhereExpressionValidatorVisitor
    extends StatelessTraverseNoExpressionVisitor<Boolean> {
    private boolean isUpdatable = true;
    private final PTable table;
    private final Set<PColumn> pkColumns;
    private final Set<PColumn> nonPKColumns;

    public ViewWhereExpressionValidatorVisitor(PTable table, Set<PColumn> pkColumns,
      Set<PColumn> nonPKColumns) {
      this.table = table;
      this.pkColumns = pkColumns;
      this.nonPKColumns = nonPKColumns;
    }

    public boolean isUpdatable() {
      return isUpdatable;
    }

    @Override
    public Boolean defaultReturn(Expression node, List<Boolean> l) {
      // We only hit this if we're trying to traverse somewhere
      // in which we don't have a visitLeave that returns non null
      isUpdatable = false;
      return null;
    }

    @Override
    public Iterator<Expression> visitEnter(AndExpression node) {
      return node.getChildren().iterator();
    }

    @Override
    public Boolean visitLeave(AndExpression node, List<Boolean> l) {
      return l.isEmpty() ? null : Boolean.TRUE;
    }

    @Override
    public Iterator<Expression> visitEnter(ComparisonExpression node) {
      if (
        node.getFilterOp() == CompareOperator.EQUAL && node.getChildren().get(1).isStateless()
          && node.getChildren().get(1).getDeterminism() == Determinism.ALWAYS
      ) {
        return Iterators.singletonIterator(node.getChildren().get(0));
      }
      return super.visitEnter(node);
    }

    @Override
    public Boolean visitLeave(ComparisonExpression node, List<Boolean> l) {
      return l.isEmpty() ? null : Boolean.TRUE;
    }

    @Override
    public Iterator<Expression> visitEnter(IsNullExpression node) {
      return node.isNegate() ? super.visitEnter(node) : node.getChildren().iterator();
    }

    @Override
    public Boolean visitLeave(IsNullExpression node, List<Boolean> l) {
      // Nothing to do as we've already set the position to an empty byte array
      return l.isEmpty() ? null : Boolean.TRUE;
    }

    @Override
    public Boolean visit(RowKeyColumnExpression node) {
      pkColumns.add(table.getPKColumns().get(node.getPosition()));
      return Boolean.TRUE;
    }

    @Override
    public Boolean visit(KeyValueColumnExpression node) {
      try {
        if (nonPKColumns != null) {
          nonPKColumns.add(table.getColumnFamily(node.getColumnFamily())
            .getPColumnForColumnQualifier(node.getColumnQualifier()));
        }
      } catch (SQLException e) {
        throw new RuntimeException(e); // Impossible
      }
      return Boolean.TRUE;
    }

    @Override
    public Boolean visit(SingleCellColumnExpression node) {
      return visit(node.getKeyValueExpression());
    }
  }

  private static class VarbinaryDatum implements PDatum {

    @Override
    public boolean isNullable() {
      return false;
    }

    @Override
    public PDataType getDataType() {
      return PVarbinary.INSTANCE;
    }

    @Override
    public Integer getMaxLength() {
      return null;
    }

    @Override
    public Integer getScale() {
      return null;
    }

    @Override
    public SortOrder getSortOrder() {
      return SortOrder.getDefault();
    }

  }

  private class CreateTableMutationPlan extends BaseMutationPlan {

    private final MetaDataClient client;
    private final CreateTableStatement finalCreate;
    private final byte[][] splits;
    private final PTable parent;
    private final String viewStatement;
    private final ViewType viewType;
    private final byte[][] viewColumnConstants;
    private final BitSet isViewColumnReferenced;

    private final byte[] rowKeyMatcher;
    private final PhoenixConnection connection;

    private CreateTableMutationPlan(StatementContext context, MetaDataClient client,
      CreateTableStatement finalCreate, byte[][] splits, PTable parent, String viewStatement,
      ViewType viewType, byte[] rowKeyMatcher, byte[][] viewColumnConstants,
      BitSet isViewColumnReferenced, PhoenixConnection connection) {
      super(context, CreateTableCompiler.this.operation);
      this.client = client;
      this.finalCreate = finalCreate;
      this.splits = splits;
      this.parent = parent;
      this.viewStatement = viewStatement;
      this.viewType = viewType;
      this.rowKeyMatcher = rowKeyMatcher;
      this.viewColumnConstants = viewColumnConstants;
      this.isViewColumnReferenced = isViewColumnReferenced;
      this.connection = connection;
    }

    @Override
    public MutationState execute() throws SQLException {
      try {
        return client.createTable(finalCreate, splits, parent, viewStatement, viewType,
          MetaDataUtil.getViewIndexIdDataType(), rowKeyMatcher, viewColumnConstants,
          isViewColumnReferenced);
      } finally {
        if (client.getConnection() != connection) {
          client.getConnection().close();
        }
      }
    }

    @Override
    public ExplainPlan getExplainPlan() throws SQLException {
      return new ExplainPlan(Collections.singletonList("CREATE TABLE"));
    }
  }
}
