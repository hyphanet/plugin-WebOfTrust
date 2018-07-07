package plugins.WebOfTrust.util;

import java.util.ArrayDeque;
import java.util.Iterator;

/***
 * A wrapper around class {@link ArrayDeque} which automatically obeys a size limit.
 * Once the limit is reached adding an element to the tail will remove the head element. */
public final class LimitedArrayDeque<T> implements Cloneable, Iterable<T> {

	private final ArrayDeque<T> mQueue;

	private final int mSizeLimit;


	public LimitedArrayDeque(int sizeLimit) {
		if(sizeLimit < 1)
			throw new IllegalArgumentException("sizeLimit is < 1: " + sizeLimit);
		
		mQueue = new ArrayDeque<T>();
		mSizeLimit = sizeLimit;
	}

	/** Copy-constructor for {@link #clone()}. */
	private LimitedArrayDeque(LimitedArrayDeque<T> original) {
		mQueue = original.mQueue.clone();
		mSizeLimit = original.mSizeLimit;
	}

	public T addLast(T element) {
		T result = null;
		
		if(mQueue.size() >= mSizeLimit)
			result = mQueue.removeFirst();
		
		mQueue.addLast(element);
		
		return result;
	}

	public void clear() {
		mQueue.clear();
	}

	public T peekFirst() {
		return mQueue.peekFirst();
	}

	public T peekLast() {
		return mQueue.peekLast();
	}

	public int size() {
		return mQueue.size();
	}

	public int sizeLimit() {
		return mSizeLimit;
	}

	@Override public LimitedArrayDeque<T> clone() {
		return new LimitedArrayDeque<>(this);
	}

	@Override public Iterator<T> iterator() {
		return mQueue.iterator();
	}

}
