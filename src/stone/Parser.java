package stone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import stone.ast.ASTLeaf;
import stone.ast.ASTList;
import stone.ast.ASTree;

public class Parser {
	protected static abstract class Element {
		protected abstract void parse(Lexer lexer, List<ASTree> res)
				throws ParseException;
		protected abstract boolean match(Lexer lexer) throws ParseException;
	}
	
	protected static class Tree extends Element {
		protected Parser parser;
		protected Tree(Parser p) {parser = p;}
		protected void parse(Lexer lexer, List<ASTree> res)
			throws ParseException
			{
				res.add(parser.parse(lexer));
			}
		protected boolean match(Lexer lexer) throws ParseException {
			return parser.match(lexer);
		}
	}
	
	protected static class OrTree extends Element {
		protected Parser[] parsers;
		protected OrTree(Parser[] p) {parsers = p;}
		protected void parse(Lexer lexer, List<ASTree> res) throws ParseException {
			Parser p = choose(lexer);
			if ( p == null)
				throw new ParseException(lexer.peek(0));
			else
				res.add(p.parse(lexer));
		}
		protected boolean match(Lexer lexer) throws ParseException {
			return choose(lexer) != null;
		}
		protected Parser choose(Lexer lexer) throws ParseException {
			for (Parser p: parsers)
				if(p.match(lexer))
					return p;
			
			return null;
		}
		
		protected void insert(Parser p) {
			Parser[] newParsers = new Parser[parsers.length + 1];
			newParsers[0] = p;
			System.arraycopy(parsers, 0, newParsers, 1, parsers.length);
			parsers = newParsers;
		}
	}
	
	protected static class Repeat extends Element {
		protected Parser parser;
		protected boolean onlyOnce;
		protected Repeat(Parser p, boolean once) { parser = p; onlyOnce = once;}
		protected void parse(Lexer lexer, List<ASTree> res) throws ParseException {
			while(parser.match(lexer)) {
				ASTree t = parser.parse(lexer);
				if(t.getClass() != ASTList.class || t.numChildren() > 0)
					res.add(t);
				if(onlyOnce)
					break;
			}
		}
		protected boolean match(Lexer lexer) throws ParseException {
			return parser.match(lexer);
		}
	}
	
	protected static abstract class AToken extends Element {
		protected Factory factory;
		protected AToken(Class<? extends ASTLeaf> type) {
			if(type == null)
				type = ASTLeaf.class;
			factory = Factory.get(type, Token.class);
		}
		protected void parse(Lexer lexer, List<ASTree> res) throws ParseException {
			Token t = lexer.read();
			if(test(t)) {
				ASTree leaf = factory.make(t);
				res.add(leaf);
			}
			else
				throw new ParseException(t);
		}
		protected boolean match(Lexer lexer) throws ParseException {
			return test(lexer.peek(0));
		}
		protected abstract boolean test(Token t);
	}
	
	protected static class IdToken extends AToken {
		HashSet<String> reserved;
		protected IdToken(Class<? extends ASTLeaf> type, HashSet<String> r) {
			super(type);
			reserved = r != null ? r : new HashSet<String>();
		}
		@Override
		protected boolean test(Token t) {
			return t.isIdentifier() && !reserved.contains(t.getText());
		}
	}
	
	protected static class NumToken extends AToken {
		protected NumToken(Class<? extends ASTLeaf> type) {super(type);}
		protected boolean test(Token t){return t.isNumber();}
	}
	
	protected static class StrToken extends AToken {
		protected StrToken(Class<? extends ASTLeaf> type) {super(type);}
		protected boolean test(Token t) {return t.isString();}
	}
	
	protected static class Leaf extends Element{
		protected String[] tokens;
		protected Leaf(String[] pat) {tokens = pat;}

		@Override
		protected void parse(Lexer lexer, List<ASTree> res)
				throws ParseException {
			Token t = lexer.read();
			if(t.isIdentifier())
				for(String token: tokens)
					if(token.equals(t.getText())){
						find(res, t);
						return;
					}
			
			if(tokens.length > 0)
				throw new ParseException(tokens[0] + " expected", t);
			else
				throw new ParseException(t);
		}
		protected void find(List<ASTree> res, Token t){
			res.add(new ASTLeaf(t));
		}

		@Override
		protected boolean match(Lexer lexer) throws ParseException {
			Token t = lexer.peek(0);
			if(t.isIdentifier())
				for(String token: tokens)
					if(token.equals(t.getText()))
						return true;
			
			return false;
		}
		
	}
	
	protected static class Skip extends Leaf {
		protected Skip(String[] t) {super(t);}
		protected void find(List<ASTree> res, Token t){}
	}
	
	public static class Precedence {
		int value;
		boolean leftAssoc;
		public Precedence(int v, boolean a){
			value = v; leftAssoc = a;
		}
	}
	
	public static class Operators extends HashMap<String,Precedence> {
		public static boolean LEFT= true;
		public static boolean RIGHT = false;
		public void add(String name, int prec, boolean leftAssoc) {
			put(name, new Precedence(prec, leftAssoc));
		}
	}
	
	protected static class Expr extends Element {
		protected Factory factory;
		protected Operators ops;
		protected Parser factor;
		protected Expr(Class<? extends ASTree> clazz, Parser exp, Operators map) {
			factory = Factory.getForASTList(clazz);
			ops = map;
			factor = exp;
		}
		@Override
		protected void parse(Lexer lexer, List<ASTree> res)
				throws ParseException {
			ASTree right=factor.parse(lexer);
			Precedence prec;
			while((prec = nextOperator(lexer)) != null)
				right = doShift(lexer, right, prec.value);
			
			res.add(right);
		}
		private ASTree doShift(Lexer lexer, ASTree left, int prec) throws ParseException {
			ArrayList<ASTree> list = new ArrayList<ASTree>();
			list.add(left);
			list.add(new ASTLeaf(lexer.read()));
			ASTree right = factor.parse(lexer);
			Precedence next;
			while((next = nextOperator(lexer)) != null && rightIsExpr(prec, next))
				right = doShift(lexer, right, next.value);
			
			list.add(right);
			return factory.make(list);
		}
		private Precedence nextOperator(Lexer lexer) throws ParseException{
			Token t = lexer.peek(0);
			if(t.isIdentifier())
				return ops.get(t.getText());
			else
				return null;
		}
		private static boolean rightIsExpr(int prec, Precedence nextPrec) {
			if(nextPrec.leftAssoc)
				return prec < nextPrec.value;
			else
				return prec <= nextPrec.value;
		}
		@Override
		protected boolean match(Lexer lexer) throws ParseException {
			return factor.match(lexer);
		}
	}
}
