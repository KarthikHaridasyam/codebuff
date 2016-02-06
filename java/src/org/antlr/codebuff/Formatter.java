package org.antlr.codebuff;

import org.antlr.groom.JavaBaseListener;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;

import java.util.Collections;
import java.util.List;

public class Formatter extends JavaBaseListener {
	public static final double MAX_CONTEXT_DIFF_THRESHOLD = 0.1; // anything more than 10% different is probably too far

	protected StringBuilder output = new StringBuilder();
	protected ParserRuleContext root;
	protected CommonTokenStream tokens; // track stream so we can examine previous tokens
	protected CodekNNClassifier newlineClassifier;
	protected CodekNNClassifier wsClassifier;
	protected CodekNNClassifier indentClassifier;
	protected CodekNNClassifier alignClassifier;
	protected int k;

	protected int line = 1;
	protected int charPosInLine = 0;
	protected int currentIndent = 0;

	protected int tabSize;

	public Formatter(Corpus corpus, InputDocument doc, int tabSize) {
		this.root = doc.tree;
		this.tokens = doc.tokens;
		doc.originalTokens = Tool.copy(tokens);
		Tool.wipeLineAndPositionInfo(tokens);
		newlineClassifier = new CodekNNClassifier(corpus.X,
												  corpus.injectNewlines,
												  CollectFeatures.CATEGORICAL);
		wsClassifier = new CodekNNClassifier(corpus.X,
											 corpus.injectWS,
											 CollectFeatures.CATEGORICAL);

		indentClassifier = new CodekNNClassifier(corpus.X,
												 corpus.indent,
												 CollectFeatures.CATEGORICAL);
		indentClassifier.dumpVotes = true;

		alignClassifier = new CodekNNClassifier(corpus.X,
												corpus.levelsToCommonAncestor,
												CollectFeatures.CATEGORICAL);
		k = (int)Math.sqrt(corpus.X.size());
		this.tabSize = tabSize;
	}

	public String getOutput() {
		return output.toString();
	}

	@Override
	public void visitTerminal(TerminalNode node) {
		CommonToken curToken = (CommonToken)node.getSymbol();
		if ( curToken.getType()==Token.EOF ) return;

		int i = curToken.getTokenIndex();
		if ( Tool.getNumberRealTokens(tokens, 0, i-1)<2 ) {
			return;
		}

		tokens.seek(i); // seek so that LT(1) is tokens.get(i);

		String tokText = curToken.getText();

		int[] features = CollectFeatures.getNodeFeatures(tokens, node, tabSize);
		// must set "prev end column" value as token stream doesn't have it;
		// we're tracking it as we emit tokens
		features[CollectFeatures.INDEX_PREV_END_COLUMN] = charPosInLine;

		int injectNewline = newlineClassifier.classify(k, features, MAX_CONTEXT_DIFF_THRESHOLD);
		int indent = indentClassifier.classify(k, features, MAX_CONTEXT_DIFF_THRESHOLD);
		if ( injectNewline>0 ) {
			output.append(Tool.newlines(injectNewline));
			line++;
			int levelsToCommonAncestor = alignClassifier.classify(k, features, MAX_CONTEXT_DIFF_THRESHOLD);
			if ( levelsToCommonAncestor>0 ) {
				List<? extends Tree> ancestors = Trees.getAncestors(node);
				Collections.reverse(ancestors);
				ParserRuleContext commonAncestor =
					(ParserRuleContext)ancestors.get(levelsToCommonAncestor);
				charPosInLine = commonAncestor.getStart().getCharPositionInLine();
				output.append(Tool.spaces(charPosInLine));
			}
			else {
				currentIndent += indent;
				if ( currentIndent<0 ) currentIndent = 0; // don't allow bad indents to accumulate
				charPosInLine = currentIndent;
				output.append(Tool.spaces(currentIndent));
			}
//			else { //if ( indent>0 ) {
//				output.append(Tool.spaces(currentIndent));
//				output.append(Tool.spaces(indent));
//				currentIndent += indent;
//			}
		}
		else {
			// inject whitespace instead of \n?
			int ws = wsClassifier.classify(k, features, MAX_CONTEXT_DIFF_THRESHOLD); // the class is the number of WS chars
			output.append(Tool.spaces(ws));
			charPosInLine += ws;
		}
		// update Token object with position information now that we are about
		// to emit it.
		curToken.setLine(line);
		curToken.setCharPositionInLine(charPosInLine);
		// emit
		output.append(tokText);
		charPosInLine += tokText.length();
	}

}
