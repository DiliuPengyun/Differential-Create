package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Assembly phase in calibrated turns; neither rotor speeds nor spring preload are changed. */
public final class ShaftPhases {

	/** Non-slipping contact: ratio * angle[a] - angle[b] = twist. */
	public record Contact(int a, int b, double ratio, double twist) {}
	private ShaftPhases() {}

	/**
	 * Retain the first member's angle in each connected component and fit its
	 * neighbours to the same assembly phase. Callers order surviving anchors
	 * first and omit clutch friction surfaces, whose two halves can slip.
	 */
	public static double[] align(double[] angles, List<Contact> contacts) {
		double[] aligned = angles.clone();
		var neighbours = new ArrayList<List<Contact>>(angles.length);
		for (int i = 0; i < angles.length; i++) neighbours.add(new ArrayList<>());
		for (var contact : contacts) {
			neighbours.get(contact.a).add(contact);
			neighbours.get(contact.b).add(contact);
		}
		boolean[] visited = new boolean[angles.length];
		var queue = new ArrayDeque<Integer>();
		for (int anchor = 0; anchor < angles.length; anchor++) {
			if (visited[anchor]) continue;
			visited[anchor] = true;
			queue.add(anchor);
			while (!queue.isEmpty()) {
				int from = queue.removeFirst();
				for (var contact : neighbours.get(from)) {
					int to = contact.a == from ? contact.b : contact.a;
					if (visited[to]) continue;
					aligned[to] = contact.a == from
						? contact.ratio * aligned[from] - contact.twist
						: (aligned[from] + contact.twist) / contact.ratio;
					visited[to] = true;
					queue.addLast(to);
				}
			}
		}
		return aligned;
	}
}
