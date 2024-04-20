/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.facet.range;

import java.io.IOException;
import java.util.Objects;
import org.apache.lucene.facet.MultiLongValues;
import org.apache.lucene.facet.MultiLongValuesSource;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LongValues;
import org.apache.lucene.search.LongValuesSource;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

/**
 * Represents a range over long values.
 *
 * @lucene.experimental
 */
public final class LongRange extends Range {
  /** Minimum (inclusive). */
  public final long min;

  /** Maximum (inclusive). */
  public final long max;

  // TODO: can we require fewer args? (same for
  // Double/FloatRange too)

  /** Create a LongRange. */
  public LongRange(
      String label, long minIn, boolean minInclusive, long maxIn, boolean maxInclusive) {
    super(label);

    if (!minInclusive) {
      if (minIn != Long.MAX_VALUE) {
        minIn++;
      } else {
        failNoMatch();
      }
    }

    if (!maxInclusive) {
      if (maxIn != Long.MIN_VALUE) {
        maxIn--;
      } else {
        failNoMatch();
      }
    }

    if (minIn > maxIn) {
      failNoMatch();
    }

    this.min = minIn;
    this.max = maxIn;
  }

  /** True if this range accepts the provided value. */
  public boolean accept(long value) {
    return value >= min && value <= max;
  }

  @Override
  public String toString() {
    return "LongRange(" + label + ": " + min + " to " + max + ")";
  }

  @Override
  public boolean equals(Object _that) {
    if (_that instanceof LongRange == false) {
      return false;
    }
    LongRange that = (LongRange) _that;
    return that.label.equals(this.label) && that.min == this.min && that.max == this.max;
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, min, max);
  }

  private static class ValueSourceQuery extends Query {
    private final LongRange range;
    private final Query fastMatchQuery;
    private final LongValuesSource valueSource;

    ValueSourceQuery(LongRange range, Query fastMatchQuery, LongValuesSource valueSource) {
      this.range = range;
      this.fastMatchQuery = fastMatchQuery;
      this.valueSource = valueSource;
    }

    @Override
    public boolean equals(Object other) {
      return sameClassAs(other) && equalsTo(getClass().cast(other));
    }

    private boolean equalsTo(ValueSourceQuery other) {
      return range.equals(other.range)
          && Objects.equals(fastMatchQuery, other.fastMatchQuery)
          && valueSource.equals(other.valueSource);
    }

    @Override
    public int hashCode() {
      return classHash() + 31 * Objects.hash(range, fastMatchQuery, valueSource);
    }

    @Override
    public String toString(String field) {
      return "Filter(" + range.toString() + ")";
    }

    @Override
    public void visit(QueryVisitor visitor) {
      visitor.visitLeaf(this);
    }

    @Override
    public Query rewrite(IndexSearcher indexSearcher) throws IOException {
      if (fastMatchQuery != null) {
        final Query fastMatchRewritten = fastMatchQuery.rewrite(indexSearcher);
        if (fastMatchRewritten != fastMatchQuery) {
          return new ValueSourceQuery(range, fastMatchRewritten, valueSource);
        }
      }
      return super.rewrite(indexSearcher);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
        throws IOException {
      final Weight fastMatchWeight =
          fastMatchQuery == null
              ? null
              : searcher.createWeight(fastMatchQuery, ScoreMode.COMPLETE_NO_SCORES, 1f);

      return new ConstantScoreWeight(this, boost) {
        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
          final int maxDoc = context.reader().maxDoc();

          final DocIdSetIterator approximation;
          if (fastMatchWeight == null) {
            approximation = DocIdSetIterator.all(maxDoc);
          } else {
            Scorer s = fastMatchWeight.scorer(context);
            if (s == null) {
              return null;
            }
            approximation = s.iterator();
          }

          final LongValues values = valueSource.getValues(context, null);
          final TwoPhaseIterator twoPhase =
              new TwoPhaseIterator(approximation) {
                @Override
                public boolean matches() throws IOException {
                  return values.advanceExact(approximation.docID())
                      && range.accept(values.longValue());
                }

                @Override
                public float matchCost() {
                  return 100; // TODO: use cost of range.accept()
                }
              };
          final var scorer = new ConstantScoreScorer(this, score(), scoreMode, twoPhase);
          return new ScorerSupplier() {
            @Override
            public Scorer get(long leadCost) throws IOException {
              return scorer;
            }

            @Override
            public long cost() {
              return scorer.iterator().cost();
            }
          };
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return valueSource.isCacheable(ctx);
        }
      };
    }
  }

  private static class MultiValueSourceQuery extends Query {
    private final LongRange range;
    private final Query fastMatchQuery;
    private final MultiLongValuesSource valuesSource;

    MultiValueSourceQuery(
        LongRange range, Query fastMatchQuery, MultiLongValuesSource valuesSource) {
      this.range = range;
      this.fastMatchQuery = fastMatchQuery;
      this.valuesSource = valuesSource;
    }

    @Override
    public boolean equals(Object other) {
      return sameClassAs(other) && equalsTo(getClass().cast(other));
    }

    private boolean equalsTo(MultiValueSourceQuery other) {
      return range.equals(other.range)
          && Objects.equals(fastMatchQuery, other.fastMatchQuery)
          && valuesSource.equals(other.valuesSource);
    }

    @Override
    public int hashCode() {
      return classHash() + 31 * Objects.hash(range, fastMatchQuery, valuesSource);
    }

    @Override
    public String toString(String field) {
      return "Filter(" + range.toString() + ")";
    }

    @Override
    public void visit(QueryVisitor visitor) {
      visitor.visitLeaf(this);
    }

    @Override
    public Query rewrite(IndexSearcher indexSearcher) throws IOException {
      if (fastMatchQuery != null) {
        final Query fastMatchRewritten = fastMatchQuery.rewrite(indexSearcher);
        if (fastMatchRewritten != fastMatchQuery) {
          return new MultiValueSourceQuery(range, fastMatchRewritten, valuesSource);
        }
      }
      return super.rewrite(indexSearcher);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
        throws IOException {
      final Weight fastMatchWeight =
          fastMatchQuery == null
              ? null
              : searcher.createWeight(fastMatchQuery, ScoreMode.COMPLETE_NO_SCORES, 1f);

      return new ConstantScoreWeight(this, boost) {
        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
          final int maxDoc = context.reader().maxDoc();

          final DocIdSetIterator approximation;
          if (fastMatchWeight == null) {
            approximation = DocIdSetIterator.all(maxDoc);
          } else {
            Scorer s = fastMatchWeight.scorer(context);
            if (s == null) {
              return null;
            }
            approximation = s.iterator();
          }

          final MultiLongValues values = valuesSource.getValues(context);
          final TwoPhaseIterator twoPhase =
              new TwoPhaseIterator(approximation) {
                @Override
                public boolean matches() throws IOException {
                  if (values.advanceExact(approximation.docID()) == false) {
                    return false;
                  }

                  for (int i = 0; i < values.getValueCount(); i++) {
                    if (range.accept(values.nextValue())) {
                      return true;
                    }
                  }
                  return false;
                }

                @Override
                public float matchCost() {
                  return 100; // TODO: use cost of range.accept()
                }
              };
          final var scorer = new ConstantScoreScorer(this, score(), scoreMode, twoPhase);
          return new ScorerSupplier() {
            @Override
            public Scorer get(long leadCost) throws IOException {
              return scorer;
            }

            @Override
            public long cost() {
              return scorer.iterator().cost();
            }
          };
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return valuesSource.isCacheable(ctx);
        }
      };
    }
  }

  /**
   * Create a Query that matches documents in this range
   *
   * <p>The query will check all documents that match the provided match query, or every document in
   * the index if the match query is null.
   *
   * <p>If the value source is static, eg an indexed numeric field, it may be faster to use {@link
   * org.apache.lucene.search.PointRangeQuery}
   *
   * @param fastMatchQuery a query to use as a filter
   * @param valueSource the source of values for the range check
   */
  public Query getQuery(Query fastMatchQuery, LongValuesSource valueSource) {
    return new ValueSourceQuery(this, fastMatchQuery, valueSource);
  }

  /**
   * Create a Query that matches documents in this range
   *
   * <p>The query will check all documents that match the provided match query, or every document in
   * the index if the match query is null.
   *
   * <p>If the value source is static, eg an indexed numeric field, it may be faster to use {@link
   * org.apache.lucene.search.PointRangeQuery}
   *
   * @param fastMatchQuery a query to use as a filter
   * @param valuesSource the source of values for the range check
   */
  public Query getQuery(Query fastMatchQuery, MultiLongValuesSource valuesSource) {
    LongValuesSource singleValues = MultiLongValuesSource.unwrapSingleton(valuesSource);
    if (singleValues != null) {
      return new ValueSourceQuery(this, fastMatchQuery, singleValues);
    } else {
      return new MultiValueSourceQuery(this, fastMatchQuery, valuesSource);
    }
  }
}
