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
package org.apache.phoenix.expression.util.bson;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.phoenix.parse.AndParseNode;
import org.apache.phoenix.parse.BetweenParseNode;
import org.apache.phoenix.parse.BsonExpressionParser;
import org.apache.phoenix.parse.DocumentFieldBeginsWithParseNode;
import org.apache.phoenix.parse.DocumentFieldContainsParseNode;
import org.apache.phoenix.parse.DocumentFieldExistsParseNode;
import org.apache.phoenix.parse.DocumentFieldSizeParseNode;
import org.apache.phoenix.parse.DocumentFieldTypeParseNode;
import org.apache.phoenix.parse.EqualParseNode;
import org.apache.phoenix.parse.GreaterThanOrEqualParseNode;
import org.apache.phoenix.parse.GreaterThanParseNode;
import org.apache.phoenix.parse.InListParseNode;
import org.apache.phoenix.parse.LessThanOrEqualParseNode;
import org.apache.phoenix.parse.LessThanParseNode;
import org.apache.phoenix.parse.LiteralParseNode;
import org.apache.phoenix.parse.NotEqualParseNode;
import org.apache.phoenix.parse.NotParseNode;
import org.apache.phoenix.parse.OrParseNode;
import org.apache.phoenix.parse.ParseNode;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNumber;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL style condition expression evaluation support.
 */
public final class SQLComparisonExpressionUtils {

  private SQLComparisonExpressionUtils() {
    // empty
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(SQLComparisonExpressionUtils.class);

  /**
   * Evaluate the given condition expression on the BSON Document.
   * @param conditionExpression      The condition expression consisting of operands, operators.
   * @param rawBsonDocument          The BSON Document on which the condition expression is
   *                                 evaluated.
   * @param comparisonValuesDocument The BSON Document consisting of place-holder key-value pairs.
   * @return True if the evaluation is successful, False otherwise.
   */
  public static boolean evaluateConditionExpression(final String conditionExpression,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    if (rawBsonDocument == null || conditionExpression == null) {
      LOGGER.warn("Document and/or Condition Expression document are empty. Document: {}, "
        + "conditionExpression: {}", rawBsonDocument, conditionExpression);
      return false;
    }
    return evaluateExpression(conditionExpression, rawBsonDocument, comparisonValuesDocument, null);
  }

  /**
   * Evaluate the given condition expression on the BSON Document.
   * @param conditionExpression      The condition expression consisting of operands, operators.
   * @param rawBsonDocument          The BSON Document on which the condition expression is
   *                                 evaluated.
   * @param comparisonValuesDocument The BSON Document consisting of place-holder key-value pairs.
   * @param keyAliasDocument         The BSON Document consisting of place-holder for keys.
   * @return True if the evaluation is successful, False otherwise.
   */
  public static boolean evaluateConditionExpression(final String conditionExpression,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument,
    final BsonDocument keyAliasDocument) {
    if (rawBsonDocument == null || conditionExpression == null) {
      LOGGER.warn("Document and/or Condition Expression document are empty. Document: {}, "
        + "conditionExpression: {}", rawBsonDocument, conditionExpression);
      return false;
    }
    return evaluateExpression(conditionExpression, rawBsonDocument, comparisonValuesDocument,
      keyAliasDocument);
  }

  /**
   * Generates ParseNode based tree and performs the condition expression evaluation on it.
   * @param conditionExpression      The condition expression consisting of operands, operators.
   * @param rawBsonDocument          The BSON Document on which the condition expression is
   *                                 evaluated.
   * @param comparisonValuesDocument The BSON Document consisting of place-holder key-value pairs.
   * @param keyAliasDocument         The BSON Document consisting of place-holder for keys.
   * @return True if the evaluation is successful, False otherwise.
   */
  private static boolean evaluateExpression(final String conditionExpression,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument,
    final BsonDocument keyAliasDocument) {
    BsonExpressionParser bsonExpressionParser = new BsonExpressionParser(conditionExpression);
    ParseNode parseNode;
    try {
      parseNode = bsonExpressionParser.parseExpression();
    } catch (SQLException e) {
      LOGGER.error("Expression {} could not be evaluated.", conditionExpression, e);
      throw new RuntimeException("Expression could not be evaluated: " + conditionExpression, e);
    }
    List<String> sortedKeyNames;
    if (keyAliasDocument == null || keyAliasDocument.isEmpty()) {
      sortedKeyNames = Collections.emptyList();
    } else {
      sortedKeyNames = new ArrayList<>(keyAliasDocument.keySet());
      sortedKeyNames.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }
    return evaluateExpression(parseNode, rawBsonDocument, comparisonValuesDocument,
      keyAliasDocument, sortedKeyNames);
  }

  /**
   * Evaluate the parseNode directly.
   * @param parseNode                The root ParseNode of the parse tree.
   * @param rawBsonDocument          BSON Document value of the Cell.
   * @param comparisonValuesDocument BSON Document with place-holder values.
   * @param keyAliasDocument         The BSON Document consisting of place-holder for keys.
   * @param sortedKeyNames           The document key names in the descending sorted order of their
   *                                 string length.
   * @return True if the evaluation is successful, False otherwise.
   */
  private static boolean evaluateExpression(final ParseNode parseNode,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument,
    final BsonDocument keyAliasDocument, final List<String> sortedKeyNames) {

    // In Phoenix, usually every ParseNode has corresponding Expression class. The expression
    // is evaluated on the Tuple. However, the expression requires data type of PDataType instance.
    // This case is different: we need to evaluate the parse node on the document, not on Tuple.
    // Therefore, for the purpose of evaluating the BSON Condition Expression, we cannot rely on
    // existing Expression based framework.
    // Here, we directly perform the evaluation on the parse nodes. AND, OR, NOT logical operators
    // need to evaluate the parseNodes in loop/recursion similar to how AndExpression, OrExpression,
    // NotExpression logical expressions evaluate.
    if (parseNode instanceof DocumentFieldExistsParseNode) {
      final DocumentFieldExistsParseNode documentFieldExistsParseNode =
        (DocumentFieldExistsParseNode) parseNode;
      final LiteralParseNode fieldKey =
        (LiteralParseNode) documentFieldExistsParseNode.getChildren().get(0);
      String fieldName = (String) fieldKey.getValue();
      fieldName = replaceExpressionFieldNames(fieldName, keyAliasDocument, sortedKeyNames);
      return documentFieldExistsParseNode.isExists() == exists(fieldName, rawBsonDocument);
    } else if (parseNode instanceof DocumentFieldBeginsWithParseNode) {
      final DocumentFieldBeginsWithParseNode documentFieldBeginsWithParseNode =
        (DocumentFieldBeginsWithParseNode) parseNode;
      final LiteralParseNode fieldKey =
        (LiteralParseNode) documentFieldBeginsWithParseNode.getFieldKey();
      final LiteralParseNode value = (LiteralParseNode) documentFieldBeginsWithParseNode.getValue();
      String fieldName = (String) fieldKey.getValue();
      fieldName = replaceExpressionFieldNames(fieldName, keyAliasDocument, sortedKeyNames);
      final String prefixValue = (String) value.getValue();
      return beginsWith(fieldName, prefixValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof DocumentFieldContainsParseNode) {
      final DocumentFieldContainsParseNode documentFieldContainsParseNode =
        (DocumentFieldContainsParseNode) parseNode;
      final LiteralParseNode fieldKey =
        (LiteralParseNode) documentFieldContainsParseNode.getFieldKey();
      final LiteralParseNode value = (LiteralParseNode) documentFieldContainsParseNode.getValue();
      String fieldName = (String) fieldKey.getValue();
      fieldName = replaceExpressionFieldNames(fieldName, keyAliasDocument, sortedKeyNames);
      final String containsValue = (String) value.getValue();
      return contains(fieldName, containsValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof DocumentFieldTypeParseNode) {
      final DocumentFieldTypeParseNode documentFieldTypeParseNode =
        (DocumentFieldTypeParseNode) parseNode;
      final LiteralParseNode fieldKey = (LiteralParseNode) documentFieldTypeParseNode.getFieldKey();
      final LiteralParseNode value = (LiteralParseNode) documentFieldTypeParseNode.getValue();
      String fieldName = (String) fieldKey.getValue();
      fieldName = replaceExpressionFieldNames(fieldName, keyAliasDocument, sortedKeyNames);
      final String type = (String) value.getValue();
      return isFieldOfType(fieldName, type, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof EqualParseNode) {
      final EqualParseNode equalParseNode = (EqualParseNode) parseNode;
      Object lhs =
        getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames, equalParseNode.getLHS());
      final LiteralParseNode rhs = (LiteralParseNode) equalParseNode.getRHS();
      final String expectedFieldValue = (String) rhs.getValue();
      return isEquals(lhs, expectedFieldValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof NotEqualParseNode) {
      final NotEqualParseNode notEqualParseNode = (NotEqualParseNode) parseNode;
      Object lhs =
        getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames, notEqualParseNode.getLHS());
      final LiteralParseNode rhs = (LiteralParseNode) notEqualParseNode.getRHS();
      final String expectedFieldValue = (String) rhs.getValue();
      return !isEquals(lhs, expectedFieldValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof LessThanParseNode) {
      final LessThanParseNode lessThanParseNode = (LessThanParseNode) parseNode;
      Object lhs =
        getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames, lessThanParseNode.getLHS());
      final LiteralParseNode rhs = (LiteralParseNode) lessThanParseNode.getRHS();
      final String expectedFieldValue = (String) rhs.getValue();
      return lessThan(lhs, expectedFieldValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof LessThanOrEqualParseNode) {
      final LessThanOrEqualParseNode lessThanOrEqualParseNode =
        (LessThanOrEqualParseNode) parseNode;
      Object lhs = getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames,
        lessThanOrEqualParseNode.getLHS());
      final LiteralParseNode rhs = (LiteralParseNode) lessThanOrEqualParseNode.getRHS();
      final String expectedFieldValue = (String) rhs.getValue();
      return lessThanOrEquals(lhs, expectedFieldValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof GreaterThanParseNode) {
      final GreaterThanParseNode greaterThanParseNode = (GreaterThanParseNode) parseNode;
      Object lhs =
        getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames, greaterThanParseNode.getLHS());
      final LiteralParseNode rhs = (LiteralParseNode) greaterThanParseNode.getRHS();
      final String expectedFieldValue = (String) rhs.getValue();
      return greaterThan(lhs, expectedFieldValue, rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof GreaterThanOrEqualParseNode) {
      final GreaterThanOrEqualParseNode greaterThanOrEqualParseNode =
        (GreaterThanOrEqualParseNode) parseNode;
      Object lhs = getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames,
        greaterThanOrEqualParseNode.getLHS());
      final LiteralParseNode rhs = (LiteralParseNode) greaterThanOrEqualParseNode.getRHS();
      final String expectedFieldValue = (String) rhs.getValue();
      return greaterThanOrEquals(lhs, expectedFieldValue, rawBsonDocument,
        comparisonValuesDocument);
    } else if (parseNode instanceof BetweenParseNode) {
      final BetweenParseNode betweenParseNode = (BetweenParseNode) parseNode;
      Object lhs = getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames,
        betweenParseNode.getChildren().get(0));
      final LiteralParseNode betweenParseNode1 =
        (LiteralParseNode) betweenParseNode.getChildren().get(1);
      final LiteralParseNode betweenParseNode2 =
        (LiteralParseNode) betweenParseNode.getChildren().get(2);
      final String expectedFieldValue1 = (String) betweenParseNode1.getValue();
      final String expectedFieldValue2 = (String) betweenParseNode2.getValue();
      return betweenParseNode.isNegate() != between(lhs, expectedFieldValue1, expectedFieldValue2,
        rawBsonDocument, comparisonValuesDocument);
    } else if (parseNode instanceof InListParseNode) {
      final InListParseNode inListParseNode = (InListParseNode) parseNode;
      final List<ParseNode> childrenNodes = inListParseNode.getChildren();
      Object lhs = getLHS(rawBsonDocument, keyAliasDocument, sortedKeyNames, childrenNodes.get(0));
      final String[] inList = new String[childrenNodes.size() - 1];
      for (int i = 1; i < childrenNodes.size(); i++) {
        LiteralParseNode literalParseNode = (LiteralParseNode) childrenNodes.get(i);
        inList[i - 1] = ((String) literalParseNode.getValue());
      }
      return inListParseNode.isNegate()
          != in(rawBsonDocument, comparisonValuesDocument, lhs, inList);
    } else if (parseNode instanceof AndParseNode) {
      AndParseNode andParseNode = (AndParseNode) parseNode;
      List<ParseNode> children = andParseNode.getChildren();
      for (ParseNode node : children) {
        if (
          !evaluateExpression(node, rawBsonDocument, comparisonValuesDocument, keyAliasDocument,
            sortedKeyNames)
        ) {
          return false;
        }
      }
      return true;
    } else if (parseNode instanceof OrParseNode) {
      OrParseNode orParseNode = (OrParseNode) parseNode;
      List<ParseNode> children = orParseNode.getChildren();
      for (ParseNode node : children) {
        if (
          evaluateExpression(node, rawBsonDocument, comparisonValuesDocument, keyAliasDocument,
            sortedKeyNames)
        ) {
          return true;
        }
      }
      return false;
    } else if (parseNode instanceof NotParseNode) {
      NotParseNode notParseNode = (NotParseNode) parseNode;
      return !evaluateExpression(notParseNode.getChildren().get(0), rawBsonDocument,
        comparisonValuesDocument, keyAliasDocument, sortedKeyNames);
    } else {
      throw new IllegalArgumentException(
        "ParseNode " + parseNode + " is not recognized for " + "document comparison");
    }
  }

  /**
   * Return the value for the given LHS ParseNode and replace any alias using the provided
   * keyAliasDocument. The value can either be a literal or the size() function on a fieldKey in the
   * provided document.
   * @param doc              BSON Document value of the Cell.
   * @param keyAliasDocument The BSON Document consisting of place-holder for keys.
   * @param sortedKeyNames   The document key names in the descending sorted order of their string
   *                         length.
   * @param parseNode        ParseNode for either literal expression or size() function.
   * @return Literal value if parseNode is LiteralParseNode or evaluate size() function if parseNode
   *         is DocumentFieldSizeParseNode.
   */
  private static Object getLHS(final RawBsonDocument doc, final BsonDocument keyAliasDocument,
    final List<String> sortedKeyNames, ParseNode parseNode) {
    if (parseNode instanceof LiteralParseNode) {
      String fieldKey = (String) ((LiteralParseNode) parseNode).getValue();
      return replaceExpressionFieldNames(fieldKey, keyAliasDocument, sortedKeyNames);
    } else if (parseNode instanceof DocumentFieldSizeParseNode) {
      String fieldKey = (String) ((DocumentFieldSizeParseNode) parseNode).getValue();
      fieldKey = replaceExpressionFieldNames(fieldKey, keyAliasDocument, sortedKeyNames);
      return new BsonInt32(getSizeOfBsonValue(fieldKey, doc));
    }
    return null;
  }

  /**
   * Returns the size of the field of the BsonDocument at the given key. If the field is not present
   * in the document, returns 0. If the field is String, returns the length of the string. If the
   * field is Binary, returns the length of the binary data. If the field is Set/Array/Document,
   * returns the number of elements.
   * @param fieldKey        The field key for which size has to be returned.
   * @param rawBsonDocument Bson Document representing the cell value from which the field is to be
   *                        retrieved.
   */
  private static Integer getSizeOfBsonValue(final String fieldKey,
    final RawBsonDocument rawBsonDocument) {
    BsonValue topLevelValue = rawBsonDocument.get(fieldKey);
    BsonValue fieldValue = topLevelValue != null
      ? topLevelValue
      : CommonComparisonExpressionUtils.getFieldFromDocument(fieldKey, rawBsonDocument);
    if (fieldValue == null) {
      return 0;
    }
    if (fieldValue instanceof BsonString) {
      return ((BsonString) fieldValue).getValue().length();
    } else if (fieldValue instanceof BsonBinary) {
      return ((BsonBinary) fieldValue).getData().length;
    } else if (fieldValue instanceof BsonArray) {
      return ((BsonArray) fieldValue).size();
    } else if (CommonComparisonExpressionUtils.isBsonSet(fieldValue)) {
      return ((BsonArray) ((BsonDocument) fieldValue).get("$set")).size();
    } else if (fieldValue instanceof BsonDocument) {
      return ((BsonDocument) fieldValue).size();
    } else {
      throw new BsonConditionInvalidArgumentException("Unsupported type for size() function. "
        + fieldValue.getClass() + ", supported types: String, Binary, Set, Array, Document.");
    }
  }

  /**
   * Replaces expression field names with their corresponding actual field names. This method
   * supports field name aliasing by replacing placeholder expressions with actual field names from
   * the provided key alias document.
   * <p>
   * Expression field names allow users to reference field names using placeholder syntax, which is
   * useful for:
   * </p>
   * <ul>
   * <li>Avoiding conflicts with reserved words</li>
   * <li>Handling field names with special characters</li>
   * <li>Improving readability of complex expressions</li>
   * <li>Supporting dynamic field name substitution</li>
   * </ul>
   * @param fieldKey         the field key expression that may contain expression field names.
   * @param keyAliasDocument the BSON document containing mappings from expression field names to
   *                         actual field names. Each key should be an expression field name and
   *                         each value should be a BsonString containing the actual field name.
   * @param sortedKeys       the list of expression field names sorted by length in descending
   *                         order.
   * @return the field key with all expression field names replaced by their corresponding actual
   *         field names.
   */
  private static String replaceExpressionFieldNames(String fieldKey, BsonDocument keyAliasDocument,
    List<String> sortedKeys) {
    String tmpFieldKey = fieldKey;
    for (String expressionAttributeName : sortedKeys) {
      if (tmpFieldKey.contains(expressionAttributeName)) {
        String actualFieldName =
          ((BsonString) keyAliasDocument.get(expressionAttributeName)).getValue();
        tmpFieldKey = tmpFieldKey.replace(expressionAttributeName, actualFieldName);
      }
    }
    return tmpFieldKey;
  }

  /**
   * Returns true if the value of the field is comparable to the value represented by
   * {@code expectedFieldValue} as per the comparison operator represented by {@code compareOp}. The
   * comparison can happen only if the data type of both values match.
   * @param fieldKey                 The field key for which value is compared against
   *                                 expectedFieldValue.
   * @param expectedFieldValue       The literal value to compare against the field value.
   * @param compareOp                The comparison operator.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the comparison is successful, False otherwise.
   */
  private static boolean compare(final Object fieldKey, final String expectedFieldValue,
    final CommonComparisonExpressionUtils.CompareOp compareOp,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    BsonValue value;
    if (fieldKey instanceof BsonNumber) {
      value = (BsonNumber) fieldKey;
    } else {
      BsonValue topLevelValue = rawBsonDocument.get((String) fieldKey);
      value = topLevelValue != null
        ? topLevelValue
        : CommonComparisonExpressionUtils.getFieldFromDocument((String) fieldKey, rawBsonDocument);
    }
    if (value != null) {
      BsonValue compareValue = comparisonValuesDocument.get(expectedFieldValue);
      return CommonComparisonExpressionUtils.compareValues(value, compareValue, compareOp);
    }
    return false;
  }

  /**
   * Returns true if the given field exists in the document.
   * @param documentField   The document field.
   * @param rawBsonDocument Bson Document representing the cell value on which the comparison is to
   *                        be performed.
   * @return True if the given field exists in the document.
   */
  private static boolean exists(final String documentField, final RawBsonDocument rawBsonDocument) {
    BsonValue topLevelValue = rawBsonDocument.get(documentField);
    if (topLevelValue != null) {
      return true;
    }
    return CommonComparisonExpressionUtils.getFieldFromDocument(documentField, rawBsonDocument)
        != null;
  }

  /**
   * Returns true if the value of the field is less than the value represented by {@code
   * expectedFieldValue}. The comparison can happen only if the data type of both values match.
   * @param lhs                      LHS compared against expectedFieldValue. It can either be a
   *                                 fieldKey in the document or size of a field.
   * @param expectedFieldValue       The literal value to compare against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is less than expectedFieldValue.
   */
  private static boolean lessThan(final Object lhs, final String expectedFieldValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    return compare(lhs, expectedFieldValue, CommonComparisonExpressionUtils.CompareOp.LESS,
      rawBsonDocument, comparisonValuesDocument);
  }

  /**
   * Returns true if the value of the field is less than or equal to the value represented by
   * {@code expectedFieldValue}. The comparison can happen only if the data type of both values
   * match.
   * @param lhs                      LHS compared against expectedFieldValue. It can either be a
   *                                 fieldKey in the document or size of a field.
   * @param expectedFieldValue       The literal value to compare against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is less than or equal to expectedFieldValue.
   */
  private static boolean lessThanOrEquals(final Object lhs, final String expectedFieldValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    return compare(lhs, expectedFieldValue, CommonComparisonExpressionUtils.CompareOp.LESS_OR_EQUAL,
      rawBsonDocument, comparisonValuesDocument);
  }

  /**
   * Returns true if the value of the field is greater than the value represented by {@code
   * expectedFieldValue}. The comparison can happen only if the data type of both values match.
   * @param lhs                      LHS compared against expectedFieldValue. It can either be a
   *                                 fieldKey in the document or size of a field.
   * @param expectedFieldValue       The literal value to compare against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is greater than expectedFieldValue.
   */
  private static boolean greaterThan(final Object lhs, final String expectedFieldValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    return compare(lhs, expectedFieldValue, CommonComparisonExpressionUtils.CompareOp.GREATER,
      rawBsonDocument, comparisonValuesDocument);
  }

  /**
   * Returns true if the value of the field is greater than or equal to the value represented by
   * {@code expectedFieldValue}. The comparison can happen only if the data type of both values
   * match.
   * @param lhs                      LHS compared against expectedFieldValue. It can either be a
   *                                 fieldKey in the document or size of a field.
   * @param expectedFieldValue       The literal value to compare against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is greater than or equal to expectedFieldValue.
   */
  private static boolean greaterThanOrEquals(final Object lhs, final String expectedFieldValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    return compare(lhs, expectedFieldValue,
      CommonComparisonExpressionUtils.CompareOp.GREATER_OR_EQUAL, rawBsonDocument,
      comparisonValuesDocument);
  }

  /**
   * Returns true if the value of the field is greater than or equal to the value represented by
   * {@code expectedFieldValue1} and less than or equal to the value represented by
   * {@code expectedFieldValue2}. The comparison can happen only if the data type of both values
   * match.
   * @param lhs                      LHS which is compared against two values. It can either be a
   *                                 fieldKey in the document or size of a field.
   * @param expectedFieldValue1      The first literal value to compare against the field value.
   * @param expectedFieldValue2      The second literal value to compare against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is greater than or equal to the value represented by
   *         expectedFieldValue1 and less than or equal to the value represented by
   *         expectedFieldValue2.
   */
  private static boolean between(final Object lhs, final String expectedFieldValue1,
    final String expectedFieldValue2, final RawBsonDocument rawBsonDocument,
    final BsonDocument comparisonValuesDocument) {
    return greaterThanOrEquals(lhs, expectedFieldValue1, rawBsonDocument, comparisonValuesDocument)
      && lessThanOrEquals(lhs, expectedFieldValue2, rawBsonDocument, comparisonValuesDocument);
  }

  /**
   * Returns true if the value of the field equals to any of the comma separated values represented
   * by {@code expectedInValues}. The equality check is successful only if the value and the data
   * type both match.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @param lhs                      LHS which is compared against expectedInValues. It can either
   *                                 be a fieldKey in the document or size of a field.
   * @param expectedInValues         The array of values for comparison, separated by comma.
   * @return True if the value of the field equals to any of the comma separated values represented
   *         by expectedInValues. The equality check is successful only if the value and the data
   *         type both match.
   */
  private static boolean in(final RawBsonDocument rawBsonDocument,
    final BsonDocument comparisonValuesDocument, final Object lhs,
    final String... expectedInValues) {
    BsonValue value;
    if (lhs instanceof BsonNumber) {
      value = (BsonNumber) lhs;
    } else {
      BsonValue topLevelValue = rawBsonDocument.get((String) lhs);
      value = topLevelValue != null
        ? topLevelValue
        : CommonComparisonExpressionUtils.getFieldFromDocument((String) lhs, rawBsonDocument);
    }
    if (value != null) {
      for (String expectedInVal : expectedInValues) {
        if (isEquals(lhs, expectedInVal, rawBsonDocument, comparisonValuesDocument)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns true if the value of the field is equal to the value represented by {@code
   * expectedFieldValue}. The equality check is successful only if the value and the data type both
   * match.
   * @param lhs                      LHS compared against expectedFieldValue. It can either be a
   *                                 fieldKey in the document or size of a field.
   * @param expectedFieldValue       The literal value to compare against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is equal to expectedFieldValue.
   */
  private static boolean isEquals(final Object lhs, final String expectedFieldValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    return compare(lhs, expectedFieldValue, CommonComparisonExpressionUtils.CompareOp.EQUALS,
      rawBsonDocument, comparisonValuesDocument);
  }

  /**
   * Returns true if the value of the field begins with the prefix value represented by
   * {@code prefixValue}. The comparison supports String and Binary data types only. For other data
   * types, throws BsonConditionInvalidArgumentException.
   * @param fieldKey                 The field key for which value is checked for prefix.
   * @param prefixValue              The prefix value to check against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field begins with prefixValue.
   * @throws BsonConditionInvalidArgumentException if unsupported data types are used.
   */
  private static boolean beginsWith(final String fieldKey, final String prefixValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument)
    throws BsonConditionInvalidArgumentException {
    BsonValue topLevelValue = rawBsonDocument.get(fieldKey);
    BsonValue fieldValue = topLevelValue != null
      ? topLevelValue
      : CommonComparisonExpressionUtils.getFieldFromDocument(fieldKey, rawBsonDocument);
    if (fieldValue == null) {
      return false;
    }
    BsonValue prefixBsonValue = comparisonValuesDocument.get(prefixValue);
    if (prefixBsonValue == null) {
      return false;
    }
    if (!prefixBsonValue.isString() && !prefixBsonValue.isBinary()) {
      throw new BsonConditionInvalidArgumentException(
        "begins_with function only supports String and Binary data types.");
    }
    if (fieldValue.isString() && prefixBsonValue.isString()) {
      String fieldStr = ((BsonString) fieldValue).getValue();
      String prefixStr = ((BsonString) prefixBsonValue).getValue();
      return fieldStr.startsWith(prefixStr);
    } else if (fieldValue.isBinary() && prefixBsonValue.isBinary()) {
      byte[] fieldBytes = fieldValue.asBinary().getData();
      byte[] prefixBytes = prefixBsonValue.asBinary().getData();
      if (prefixBytes.length > fieldBytes.length) {
        return false;
      }
      return IntStream.range(0, prefixBytes.length).noneMatch(i -> fieldBytes[i] != prefixBytes[i]);
    } else {
      return false;
    }
  }

  /**
   * Returns true if the value of the field contains the specified value. The field can be: - A
   * String that contains a particular substring - A Set that contains a particular element within
   * the set - A List that contains a particular element within the list For other data types,
   * returns false.
   * @param fieldKey                 The field key for which value is checked for contains.
   * @param containsValue            The value to check against the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field contains containsValue, false otherwise.
   */
  private static boolean contains(final String fieldKey, final String containsValue,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    BsonValue topLevelValue = rawBsonDocument.get(fieldKey);
    BsonValue fieldValue = topLevelValue != null
      ? topLevelValue
      : CommonComparisonExpressionUtils.getFieldFromDocument(fieldKey, rawBsonDocument);
    if (fieldValue == null) {
      return false;
    }
    BsonValue containsBsonValue = comparisonValuesDocument.get(containsValue);
    if (containsBsonValue == null) {
      return false;
    }

    if (fieldValue.isString()) {
      if (!containsBsonValue.isString()) {
        return false;
      }
      String fieldStr = ((BsonString) fieldValue).getValue();
      String containsStr = ((BsonString) containsBsonValue).getValue();
      return fieldStr.contains(containsStr);
    } else if (fieldValue.isArray()) {
      List<BsonValue> fieldValues = ((BsonArray) fieldValue).getValues();
      return fieldValues.stream().anyMatch(element -> areEqual(element, containsBsonValue));
    } else if (CommonComparisonExpressionUtils.isBsonSet(fieldValue)) {
      List<BsonValue> fieldValues =
        ((BsonArray) ((BsonDocument) fieldValue).get("$set")).getValues();
      return fieldValues.stream().anyMatch(element -> areEqual(element, containsBsonValue));
    }
    return false;
  }

  /**
   * Returns true if the type of the value of the field key is same as the provided type. The
   * provided type should be one of the following: {N,BS,L,B,NULL,M,S,SS,NS,BOOL}.
   * @param fieldKey                 The field key for which value is checked for field_type.
   * @param type                     The type to check against the type of the field value.
   * @param rawBsonDocument          Bson Document representing the cell value on which the
   *                                 comparison is to be performed.
   * @param comparisonValuesDocument Bson Document with values placeholder.
   * @return True if the value of the field is of the given type, false otherwise
   */
  private static boolean isFieldOfType(final String fieldKey, final String type,
    final RawBsonDocument rawBsonDocument, final BsonDocument comparisonValuesDocument) {
    BsonValue topLevelValue = rawBsonDocument.get(fieldKey);
    BsonValue fieldValue = topLevelValue != null
      ? topLevelValue
      : CommonComparisonExpressionUtils.getFieldFromDocument(fieldKey, rawBsonDocument);
    if (fieldValue == null) {
      return false;
    }
    BsonValue typeBsonVal = comparisonValuesDocument.get(type);
    if (typeBsonVal == null) {
      throw new BsonConditionInvalidArgumentException(
        "Value for type was not found in the comparison values document.");
    }
    switch (((BsonString) typeBsonVal).getValue()) {
      case "S":
        return fieldValue.isString();
      case "N":
        return fieldValue.isNumber();
      case "B":
        return fieldValue.isBinary();
      case "BOOL":
        return fieldValue.isBoolean();
      case "NULL":
        return fieldValue.isNull();
      case "L":
        return fieldValue.isArray();
      case "M":
        return fieldValue.isDocument();
      case "SS":
        return CommonComparisonExpressionUtils.isBsonStringSet(fieldValue);
      case "NS":
        return CommonComparisonExpressionUtils.isBsonNumberSet(fieldValue);
      case "BS":
        return CommonComparisonExpressionUtils.isBsonBinarySet(fieldValue);
      default:
        throw new BsonConditionInvalidArgumentException(
          "Unsupported type in field_type() for BsonConditionExpression: " + type
            + ", valid types: valid types: {N,BS,L,B,NULL,M,S,SS,NS,BOOL}");
    }
  }

  private static boolean areEqual(BsonValue value1, BsonValue value2) {
    if (value1 == null && value2 == null) {
      return true;
    }
    if (value1 == null || value2 == null) {
      return false;
    }
    return value1.equals(value2);
  }

}
