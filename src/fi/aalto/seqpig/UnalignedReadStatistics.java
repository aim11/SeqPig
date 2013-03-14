// Copyright (c) 2012 Aalto University
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package fi.aalto.seqpig;

import java.util.Iterator;

import java.io.IOException;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.EvalFunc;
import org.apache.pig.Accumulator;
import org.apache.pig.Algebraic;
import org.apache.pig.PigException;
import org.apache.pig.PigWarning;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.util.WrappedIOException;

import fi.tkk.ics.hadoop.bam.FormatConstants;

public class UnalignedReadStatistics extends EvalFunc<Tuple> implements Algebraic, Accumulator<Tuple>
{
	protected final static int READ_LENGTH = 100;
	//protected final static int STATS_PER_POS = 5; // one per base: A, C, G, T, N
	protected final static char[] BASES = new char[]{ 'A', 'C', 'G', 'T', 'N' };
	protected final static int STATS_PER_POS = 1 + BASES.length; // position + one per each A, C, G, T, N
	//protected final static int NUM_FIELDS_OUTPUT = (STATS_PER_POS * READ_LENGTH); // for each position, one value for
	protected final static int NUM_FIELDS_OUTPUT = READ_LENGTH;


	private static TupleFactory mTupleFactory = TupleFactory.getInstance();

	protected static int map_base_to_int(char c) {
		switch(c) {
			case 'A':
				return 0;
			case 'C':
				return 1;
			case 'G':
				return 2;
			case 'T':
				return 3;
			case 'N':
				return 4;
			default:
				throw new RuntimeException("invalid base character " + c);
		}
	}

	protected static void initTuple(Tuple tpl) throws Exception {
		for (int i = 0; i < tpl.size(); ++i) {
			Tuple column = mTupleFactory.newTuple(STATS_PER_POS);
			column.set(0, i); // position
			for (int j = 1; j < STATS_PER_POS; ++j)
				column.set(j, 0L); // counters
			tpl.set(i, column);
		}
	}

	// accumulating_tpl is tuple that follows output convention, new_tpl is (readbases, basequals) pair
	protected static void processTuple(Tuple accumulating_tpl, Tuple new_tpl) throws Exception {
		String sequence = (String)new_tpl.get(0);
		String basequals = (String)new_tpl.get(1);

		assert(new_tpl.size() == READ_LENGTH);
		assert(sequence.length() == READ_LENGTH);
		assert(sequence.length() == basequals.length());

		for(int pos=0; pos < sequence.length(); ++pos) {
			int readbase_int = map_base_to_int(sequence.charAt(pos));
			int readbasequal_int = (int)basequals.charAt(pos) - FormatConstants.SANGER_OFFSET;

			// first update base frequencies
			Tuple column = (Tuple)accumulating_tpl.get(pos);
			column.set(1+readbase_int, 1L + (Long)column.get(1+readbase_int));
			
			/*
			int tuple_index = (pos*STATS_PER_POS) + readbase_int;
			accumulating_tpl.set(tuple_index, ((Double)accumulating_tpl.get(tuple_index)).doubleValue()
					+ ((double)1.0/((double)number_of_reads)));

			// now update average base quality
			tuple_index = (pos*STATS_PER_POS) + STATS_PER_POS - 1;
			accumulating_tpl.set(tuple_index, ((Double)accumulating_tpl.get(tuple_index)).doubleValue() +
					((double)readbasequal_int/(double)number_of_reads));
			*/
		}
	}

	protected static Tuple sumColumnTuples(Tuple accum, Tuple addValue) throws ExecException {
		// sum all elements except the first, which is the position within the sequence
		assert(accum.size() == addValue.size());
		for (int i = 1; i < accum.size(); ++i) {
			accum.set(i, (Long)accum.get(i) + (Long)addValue.get(i));
		}
		return accum;
	}

	// values is bag of tuples that follow output convention
	static protected Tuple combineTuples(DataBag values) throws Exception {

		// from AVG:
		// combine is called from Intermediate and Final
		// In either case, Initial would have been called
		// before and would have sent in valid tuples
		// Hence we don't need to check if incoming bag
		// is empty

		Tuple output_tpl = mTupleFactory.newTuple(NUM_FIELDS_OUTPUT);
		initTuple(output_tpl);

		for (Iterator<Tuple> it = values.iterator(); it.hasNext();) {
			Tuple partial = it.next();

			for(int pos = 0; pos < partial.size(); ++pos) {
				output_tpl.set(pos, sumColumnTuples((Tuple)output_tpl.get(pos), (Tuple)partial.get(pos)));
			}
		}

		return output_tpl;
	}

	@Override
	public Tuple exec(Tuple input) throws IOException, org.apache.pig.backend.executionengine.ExecException {
		try {
			DataBag bag = (DataBag)input.get(0);
			long number_of_reads = bag.size();
			Iterator it = bag.iterator();

			Tuple output_tpl = TupleFactory.getInstance().newTuple(NUM_FIELDS_OUTPUT); 
			initTuple(output_tpl);

			while (it.hasNext()){
				//System.out.println("processing tuple!");
				Tuple t = (Tuple)it.next();
				processTuple(output_tpl, t);
			}

			return output_tpl;
		} catch(Exception e) {
			e.printStackTrace();
			throw new IOException("problem in exec");
		}
	}

	public String getInitial() {
		return Initial.class.getName();
	}

	public String getIntermed() {
		return Intermediate.class.getName();
	}

	public String getFinal() {
		return Final.class.getName();
	}

	static public class Initial extends EvalFunc<Tuple> {
		@Override
		public Tuple exec(Tuple input) {
			try {
				Tuple output_tpl = mTupleFactory.newTuple(NUM_FIELDS_OUTPUT);
				initTuple(output_tpl);

				DataBag bg = (DataBag)input.get(0);

				if(bg == null) return output_tpl;

				if(bg.iterator().hasNext()) {
					Tuple t = bg.iterator().next();
					processTuple(output_tpl, t); // note: we normalize later, so pretend there is only one read
				}

				return output_tpl;
			} catch(Exception e) {
				e.printStackTrace();
				return null;
			}
		}
	}

	// from AVG
	static public class Intermediate extends EvalFunc<Tuple> {
		@Override
		public Tuple exec(Tuple input) throws IOException {
			try {
				DataBag b = (DataBag)input.get(0);
				return combineTuples(b);
			} catch (ExecException ee) {
				ee.printStackTrace();
				throw ee;
			} catch (Exception e) {
				e.printStackTrace();
				int errCode = 2106;
				String msg = "Error while computing average in " + this.getClass().getSimpleName();
				throw new ExecException(msg, errCode, PigException.BUG, e);           

			}
		}
	}

	static public class Final extends EvalFunc<Tuple> {
		@Override
		public Tuple exec(Tuple input) throws IOException {
			try {
				DataBag b = (DataBag)input.get(0);
				Tuple combined = combineTuples(b);

				/*
				double number_of_reads = 0;

				// first compute the number of reads
				for(int i=0;i<4;i++) {
					number_of_reads += ((Double)combined.get(i)).doubleValue();
				}

				// then normalize
				for(int i=0;i<NUM_FIELDS_OUTPUT;i++) {
					combined.set(i, ((Double)combined.get(i)).doubleValue() / number_of_reads);
				}
				*/

				return combined;
			} catch (ExecException ee) {
				ee.printStackTrace();
				throw ee;
			} catch (Exception e) {
				e.printStackTrace();
				int errCode = 2106;
				String msg = "Error while computing UnalignedReadStatistics in " + this.getClass().getSimpleName();
				throw new ExecException(msg, errCode, PigException.BUG, e);           
			}
		}
	}

	@Override
	public Schema outputSchema(Schema input) {
		Schema columnSchema = new Schema();
		Schema tupleSchema = new Schema();

		try {
			columnSchema.add(new Schema.FieldSchema("pos", DataType.INTEGER));
			for (int i = 0; i < BASES.length; ++i)
				columnSchema.add(new Schema.FieldSchema(String.format("%cs", BASES[i]), DataType.LONG));

			for(int i = 0; i < READ_LENGTH; i++) {
				tupleSchema.add(new Schema.FieldSchema(String.format("pos_%04d", i), columnSchema, DataType.TUPLE));
			}

			return new Schema(new Schema.FieldSchema(getSchemaName(this.getClass().getName().toLowerCase(), input), tupleSchema, DataType.TUPLE));
		} catch(Exception e) { e.printStackTrace(); return null; }
	}

	/* Accumulator interface implementation */

	private Tuple accumulatingTuple = null;

	@Override
	public void accumulate(Tuple b) throws IOException {
		try {
			if(accumulatingTuple == null) {
				accumulatingTuple = mTupleFactory.newTuple(NUM_FIELDS_OUTPUT);
				initTuple(accumulatingTuple);
			}

			processTuple(accumulatingTuple, b); // note: we normalize later, so pretend there is only one read
		} catch (ExecException ee) {
			ee.printStackTrace();
			throw ee;
		} catch (Exception e) {
			e.printStackTrace();
			int errCode = 2106;
			String msg = "Error while computing average in " + this.getClass().getSimpleName();
			throw new ExecException(msg, errCode, PigException.BUG, e);           
		}
	}        

	@Override
	public void cleanup() {
		accumulatingTuple = null;
	}

	@Override
	public Tuple getValue() {
		return accumulatingTuple;
		/*
		try {
			if (accumulatingTuple != null) {
				for(int i=0;i<NUM_FIELDS_OUTPUT;i++) {
					accumulatingTuple.set(i, ((Double)accumulatingTuple.get(i)).doubleValue() / ((double)intermediateReadCount));
				}
			}
			return accumulatingTuple;
		} catch(Exception e) {
			e.printStackTrace();
			return null;
		}
		*/
	}    
}

