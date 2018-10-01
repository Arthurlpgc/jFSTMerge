package br.ufpe.cin.mergers.st;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import br.ufpe.cin.exceptions.st.ExceptionUtils;
import br.ufpe.cin.exceptions.st.StructuredMergeException;
import br.ufpe.cin.exceptions.st.TextualMergeException;
import br.ufpe.cin.files.st.FilesManager;
import br.ufpe.cin.mergers.util.st.MergeConflict;
import br.ufpe.cin.mergers.util.st.MergeContextSt;
import br.ufpe.cin.parser.st.JParser;
import br.ufpe.cin.printers.st.Prettyprinter;
import cide.gparser.ParseException;
import cide.gparser.TokenMgrError;
import de.ovgu.cide.fstgen.ast.st.FSTNode;
import de.ovgu.cide.fstgen.ast.st.FSTNonTerminal;
import de.ovgu.cide.fstgen.ast.st.FSTTerminal;
import fpfn.Difference;
import fpfn.Difference.Type;
import fpfn.FPFNUtils;
import stfpfn.ASTBranchComparator;

/**
 * Represents structured merge. Structured merge is based on the concept
 * of <i>superimposition</i> of ASTs. Superimposition merges trees recursively,
 * beginning from the root, based on structural and nominal similarities.
 * @author Guilherme
 */
public final class StructuredMerge {

	//FPFN
	public static List<FSTNode> samePstCandidates = new ArrayList<FSTNode>();

	/**
	 * Three-way structured merge of three given files.
	 * @param left
	 * @param base
	 * @param right
	 * @param context an empty MergeContext to store relevant information of the merging process.
	 * @return string representing the merge result.
	 * @throws StructuredMergeException
	 * @throws TextualMergeException
	 */
	public static String merge(File left, File base, File right, MergeContextSt context) throws StructuredMergeException, TextualMergeException {
		try {
			// parsing the files to be merged
			JParser parser = new JParser();
			FSTNode leftTree = parser.parse(left);
			FSTNode baseTree = parser.parse(base);
			FSTNode rightTree = parser.parse(right);

			/*
			 * common base left right nodes, or deleted base nodes
			 */
			FSTNode merged = merge_Left_Base_Right(leftTree, baseTree, rightTree, null, context);

			/*
			 * added left right nodes
			 */
			FSTNode l = leftTree.getDeepClone();
			FSTNode r = rightTree.getDeepClone();
			merge_Left_Right(l, baseTree, r, merged, false);
			merge_Left_Right(r, baseTree, l, merged, true);

			context.superImposedTree = merged;

			//FPFN
			processSamePositionConflicts(context);
			//processSameStmtConflicts(context);

		} catch (ParseException | FileNotFoundException | UnsupportedEncodingException | TokenMgrError ex) {
			String message = ExceptionUtils.getCauseMessage(ex);
			if(ex instanceof FileNotFoundException) //FileNotFoundException does not support custom messages
				message = "The merged file was deleted in one version.";
			throw new StructuredMergeException(message, context);
		}

		// during the parsing process, code indentation is typically lost, so we indent the code
		return FilesManager.indentCode(Prettyprinter.print(context.superImposedTree));
	}

	private static FSTNode merge_Left_Base_Right (FSTNode left, FSTNode base, FSTNode right, FSTNonTerminal target, MergeContextSt context) throws TextualMergeException{
		if(isOrdered(base) && (left != null && right!=null)){
			return merge_Left_Base_Right_Ordered(left, base, right, target,context);
		} else {
			if(left!=null)  left.setMerged();
			if(base!=null)  base.setMerged();
			if(right!=null)right.setMerged();
			if (base.compatibleWith(left) && base.compatibleWith(right)) { 
				/*
				 * the three-nodes have the same type and name
				 */
				FSTNode merged = base.getShallowClone();
				merged.setParent(target);

				if (left instanceof FSTNonTerminal && base instanceof FSTNonTerminal && right instanceof FSTNonTerminal) {
					for(FSTNode baseChild : ((FSTNonTerminal) base).getChildren()){
						FSTNode leftChild  = ((FSTNonTerminal) left).getCompatibleChild(baseChild);
						FSTNode rightChild = ((FSTNonTerminal)right).getCompatibleChild(baseChild);
						if(merged instanceof FSTNonTerminal){
							((FSTNonTerminal) merged).addChildOnMerge(merge_Left_Base_Right(leftChild, baseChild, rightChild, (FSTNonTerminal) merged,context));
						}
					}
				} else {
					mergeLeaves(left, base, right, merged);
				}
				return merged;

			} else if(base.compatibleWith(left) && !base.compatibleWith(right)){ 	
				/*
				 * only left has the same type and name
				 */
				String baseAST = FilesManager.getStringContentIntoSingleLineNoSpacing(base.printFST(0));
				String leftAST = FilesManager.getStringContentIntoSingleLineNoSpacing(left.printFST(0));
				if(baseAST.equals(leftAST)){
					return right;
				} else {
					return createConflict(left, base, right, false);
				}
			} else if(!base.compatibleWith(left) && base.compatibleWith(right)) {
				/*
				 * only right has the same type and name
				 */
				String baseAST = FilesManager.getStringContentIntoSingleLineNoSpacing(base.printFST(0));
				String rightAST = FilesManager.getStringContentIntoSingleLineNoSpacing(right.printFST(0));
				if(baseAST.equals(rightAST)){
					return left;
				} else {
					return createConflict(left, base, right, false);
				}
			} else {
				/*
				 * the three nodes have different type and name
				 */
				if(left == null && right ==null) {
					/*
					 * base node mutually deleted, 
					 * no need for further actions
					 */
				} else {
					return createConflict(left, base, right, false);
				}
			}
		}
		return null;
	}

	private static FSTNode merge_Left_Base_Right_Ordered(FSTNode left, FSTNode base,FSTNode right, FSTNonTerminal target, MergeContextSt context) throws TextualMergeException {
		if(left !=null) left.setMerged();
		if(base !=null) base.setMerged();
		if(right!=null)right.setMerged();

		if(base.compatibleType(left) && base.compatibleType(right)){
			/*
			 * the three-nodes have the same type
			 */
			FSTNode merged = base.getShallowClone();
			merged.setParent(target);

			if (left instanceof FSTNonTerminal && base instanceof FSTNonTerminal && right instanceof FSTNonTerminal) {
				/*
				 * Ordered merge considers the position a node
				 */
				for(int i = 0; i<((FSTNonTerminal) base).getChildren().size(); i++){
					FSTNode baseChild  = getChildAtPosition(base,i);
					FSTNode leftChild  = getChildAtPosition(left,i);
					FSTNode rightChild = getChildAtPosition(right,i);

					if(merged instanceof FSTNonTerminal){
						FSTNode m = ((FSTNonTerminal) merged).addChildOnMerge(merge_Left_Base_Right_Ordered(leftChild, baseChild, rightChild, (FSTNonTerminal) merged,context));
						//FPFN
						samePstCandidates.add(m);
					}
				}
			} else {
				//mergeLeaves(left, base, right, merged);

				//FPFN
				mergeLeavesOrdered(left, base, right, merged, context);
			}
			return merged;
		} else if(base.compatibleType(left) && !base.compatibleType(right)){ 	
			/*
			 * only left has the same type
			 */
			String baseAST = FilesManager.getStringContentIntoSingleLineNoSpacing(base.printFST(0));
			String leftAST = FilesManager.getStringContentIntoSingleLineNoSpacing(left.printFST(0));
			if(baseAST.equals(leftAST)){
				//FPFN
				context.branches.buildASTResultFromRight(right);

				return right;
			} else {
				return createConflict(left, base, right, false);
			}
		} else if(!base.compatibleType(left) && base.compatibleType(right)) {
			/*
			 * only right has the same type
			 */
			String baseAST = FilesManager.getStringContentIntoSingleLineNoSpacing(base.printFST(0));
			String rightAST = FilesManager.getStringContentIntoSingleLineNoSpacing(right.printFST(0));
			if(baseAST.equals(rightAST)){
				//FPFN
				context.branches.buildASTResultFromLeft(left);

				return left;
			} else {
				return createConflict(left, base, right, false);
			}
		} else if((left!=null && right!=null) && left.compatibleType(right)){
			/*
			 * left and right has the same type, but are different from base
			 */
			String leftAST  = FilesManager.getStringContentIntoSingleLineNoSpacing(left.printFST(0));
			String rightAST = FilesManager.getStringContentIntoSingleLineNoSpacing(right.printFST(0));
			if(leftAST.equals(rightAST)){
				return left;
			} else {
				return createConflict(left, base, right, false);
			}
		} else {
			if(left == null && right ==null) {
				/*
				 * base node mutually deleted, 
				 * no need for further actions
				 */
			} else {
				/*
				 * the three nodes have different type
				 */
				return createConflict(left, base, right, false);
			}
		}
		return null;
	}

	private static void merge_Left_Right(FSTNode a, FSTNode base, FSTNode b, FSTNode merged, boolean isProceessingRight){
		if(isOrdered(a)){
			merge_Left_Right_Ordered(a, base, b, merged, isProceessingRight);
		} else {
			if(a.isMerged()){
				if(a instanceof FSTNonTerminal){
					for(FSTNode aChild : ((FSTNonTerminal) a).getChildren()){
						FSTNode baseChild 	= ((FSTNonTerminal) base).getCompatibleChild(aChild);
						FSTNode bChild 		= ((FSTNonTerminal) b).getCompatibleChild(aChild);
						FSTNode mergedChild = ((FSTNonTerminal) merged).getCompatibleChild(aChild);
						if(baseChild == null){
							if(bChild == null){
								/*
								 * added node
								 */
								if(merged instanceof FSTNonTerminal){
									((FSTNonTerminal)merged).addChildOnMerge(aChild);
								}
								aChild.setMerged();
							} else {
								if(!isProceessingRight) {
									/*
									 * mutually added node
									 */
									merge_Left_Right(aChild, baseChild, bChild, merged, isProceessingRight);
								} else {
									/*
									 * already processed mutually added node in Merge_Left_Right first pass
									 */
								}
							}
						} else {
							if(mergedChild == null){
								/*
								 * Already processed deletion in Merge_Left_Base_Right
								 */
							} else {
								if(mergedChild.isConflict()){
									/*
									 * Already processed merge conflict in Merge_Left_Base_Right
									 */
								} else {
									merge_Left_Right(aChild, baseChild, bChild, mergedChild, isProceessingRight);
								}
							}
						}
					}
				}
			} else {
				String aAST = FilesManager.getStringContentIntoSingleLineNoSpacing(a.printFST(0));
				String bAST = FilesManager.getStringContentIntoSingleLineNoSpacing(b.printFST(0));
				if(aAST.equals(bAST)){ 
					/*
					 * mutually added node
					 */
					if(merged instanceof FSTNonTerminal){
						((FSTNonTerminal)merged).addChildOnMerge(a);
					}
				} else { 
					/*
					 * common added nodes mutually edited
					 */
					if(merged instanceof FSTNonTerminal){
						((FSTNonTerminal)merged).addChildOnMerge(createConflict(a, null, b, isProceessingRight));
					}
				}
				b.setMerged();
				a.setMerged();
			}
		}
	}

	private static void merge_Left_Right_Ordered(FSTNode a, FSTNode base, FSTNode b, FSTNode merged, boolean isProceessingRight){
		if(a.isMerged()){
			if(a instanceof FSTNonTerminal){
				for(int i = 0; i<((FSTNonTerminal) a).getChildren().size(); i++){
					FSTNode aChild  	= getChildAtPosition(a,i);
					FSTNode baseChild 	= getChildAtPosition(base,i);
					FSTNode bChild  	= getChildAtPosition(b,i);
					FSTNode mergedChild = getChildAtPosition(merged,i);
					if(baseChild == null){
						if(bChild == null){
							/*
							 * added node
							 */
							if(merged instanceof FSTNonTerminal){
								((FSTNonTerminal)merged).addChildOnMerge(aChild);
							}
							aChild.setMerged();
						} else {
							if(!isProceessingRight) {
								/*
								 * mutually added node
								 */
								merge_Left_Right_Ordered(aChild, baseChild, bChild, merged, isProceessingRight);
							} else {
								/*
								 * already processed mutually added node in Merge_Left_Right_Ordered first pass
								 */
							}
						}
					} else {
						if(mergedChild == null){
							/*
							 * Already processed deletion in Merge_Left_Base_Right_Ordered
							 */
						} else {
							if(mergedChild.isConflict()){
								/*
								 * Already processed merge conflict in Merge_Left_Base_Right_Ordered
								 */
							} else {
								if(hasRemainingChanges(aChild,baseChild,mergedChild)){
									merge_Left_Right_Ordered(aChild, baseChild, bChild, mergedChild, isProceessingRight);
								} else {
									/*
									 * Entire node already processed in Merge_Left_Base_Right_Ordered
									 */
								}
							}
						}
					}
				}
			}
		} else {
			String aAST = FilesManager.getStringContentIntoSingleLineNoSpacing(a.printFST(0));
			String bAST = FilesManager.getStringContentIntoSingleLineNoSpacing(b.printFST(0));
			if(aAST.equals(bAST)){ 
				/*
				 * mutually added node
				 */
				((FSTNonTerminal)merged).addChildOnMerge(a);
			} else { 
				/*
				 * common added nodes mutually edited
				 */
				if(merged instanceof FSTNonTerminal){
					FSTNode m = ((FSTNonTerminal)merged).addChildOnMerge(createConflict(a, null, b, isProceessingRight));
					//FPFN
					samePstCandidates.add(m);
				}
			}
			b.setMerged();
			a.setMerged();
		}
	}

	private static void mergeLeaves(FSTNode left, FSTNode base, FSTNode right,	FSTNode merged) throws TextualMergeException {
		String mergedBody = "";
		String leftBody  = FilesManager.getStringContentIntoSingleLineNoSpacing(((FSTTerminal) left).getBody());
		String baseBody  = FilesManager.getStringContentIntoSingleLineNoSpacing(((FSTTerminal) base).getBody());
		String rightBody = FilesManager.getStringContentIntoSingleLineNoSpacing(((FSTTerminal)right).getBody());
		if(leftBody.equals(rightBody)){
			mergedBody = ((FSTTerminal) left).getBody(); //or right
		} else if(leftBody.equals(baseBody) && !rightBody.equals(leftBody)){
			mergedBody = ((FSTTerminal) right).getBody();
		} else if(rightBody.equals(baseBody) && !leftBody.equals(rightBody)){
			mergedBody = ((FSTTerminal) left).getBody(); 
		} else {
			mergedBody = createConflict(left, base, right, false).getBody();
			merged.setConflict(true);
		}
		((FSTTerminal) merged).setBody(mergedBody);
	}

	//FPFN
	private static void mergeLeavesOrdered(FSTNode left, FSTNode base, FSTNode right, FSTNode merged, MergeContextSt context) throws TextualMergeException {
		String mergedBody = "";
		String leftBody  = FilesManager.getStringContentIntoSingleLineNoSpacing(((FSTTerminal) left).getBody());
		String baseBody  = FilesManager.getStringContentIntoSingleLineNoSpacing(((FSTTerminal) base).getBody());
		String rightBody = FilesManager.getStringContentIntoSingleLineNoSpacing(((FSTTerminal)right).getBody());
		if(leftBody.equals(rightBody)){
			mergedBody = ((FSTTerminal) left).getBody(); //or right
		} else if(leftBody.equals(baseBody) && !rightBody.equals(leftBody)){
			mergedBody = ((FSTTerminal) right).getBody();

			context.branches.buildASTResultFromRight(right);
		} else if(rightBody.equals(baseBody) && !leftBody.equals(rightBody)){
			mergedBody = ((FSTTerminal) left).getBody(); 

			context.branches.buildASTResultFromLeft(left);
		} else {
			mergedBody = createConflict(left, base, right, false).getBody();
			merged.setConflict(true);
		}
		((FSTTerminal) merged).setBody(mergedBody);
	}

	private static boolean isOrdered(FSTNode node) {
		return 	   node.getType().equals("MethodDeclarationBodyBlock") 
				|| node.getType().equals("ConstructorDeclarationBody")
				|| node.getType().equals("FieldDeclaration");
	}

	private static FSTTerminal createConflict(FSTNode left, FSTNode base, FSTNode right, boolean invertBody) {
		String baseBody  = "";
		String leftBody  = "";
		String rightBody = "";

		if(base != null){
			baseBody = (base instanceof FSTTerminal) ? ((FSTTerminal) base).getBody() +"\n" : FilesManager.prettyPrint((FSTNonTerminal) base);
		}
		if(left != null){
			leftBody = (left instanceof FSTTerminal) ? ((FSTTerminal) left).getBody() +"\n" : FilesManager.prettyPrint((FSTNonTerminal) left);
		}
		if(right != null){
			rightBody= (right instanceof FSTTerminal)? ((FSTTerminal) right).getBody()+"\n" : FilesManager.prettyPrint((FSTNonTerminal) right);
		}

		MergeConflict mc;
		if(invertBody){
			mc = new MergeConflict(rightBody, baseBody, leftBody); 
		} else {
			mc = new MergeConflict(leftBody, baseBody, rightBody);
		}

		//Workaround to pretty print merge conflicts
		String type = "MergeConflict";
		if(base !=null) type = base.getType();
		else if(left!=null) type = left.getType();
		else type = right.getType();

		FSTTerminal conflict = new FSTTerminal(type, "-", mc.toString(),"");
		conflict.setConflict(true);
		return conflict;
	}

	private static FSTNode getChildAtPosition(FSTNode node, int position){
		try {
			return((FSTNonTerminal) node).getChildren().get(position);
		} catch(Exception e){
			return null;
		}
	}

	private static boolean hasRemainingChanges(FSTNode a, FSTNode base, FSTNode merged) {
		String aAST = FilesManager.getStringContentIntoSingleLineNoSpacing(a.printFST(0));
		String bAST = FilesManager.getStringContentIntoSingleLineNoSpacing(base.printFST(0));
		String mAST = FilesManager.getStringContentIntoSingleLineNoSpacing(merged.printFST(0));
		return !aAST.equals(bAST) && !aAST.equals(mAST);
	}

	//FPFN
	private static void processSamePositionConflicts(MergeContextSt context) {
		for(FSTNode node : samePstCandidates){
			if(node!=null){
				if(node instanceof FSTTerminal){
					String body = ((FSTTerminal) node).getBody();
					body = body.replaceAll("(?m)^[ \t]*\r?\n", "");

					if(body.contains("<<<<<<<")){ //is conflict?
						FSTNode methodDecl = FPFNUtils.getMethodNode(node);

						if(methodDecl != null){
							String mergedBodyContent   = FPFNUtils.getMethodBody(methodDecl);
							String signature 		   = FPFNUtils.extractSignature(mergedBodyContent);
							List<br.ufpe.cin.mergers.util.MergeConflict> mergeConflicts 	= br.ufpe.cin.files.FilesManager.extractMergeConflicts(body);
							for(Difference jfstmergeDiff: context.differences){ //filling differences with jdime's info
								if(FPFNUtils.areSignatureEqual(signature, jfstmergeDiff.signature))
									jfstmergeDiff.jdimeBody = mergedBodyContent;
							}
							for(br.ufpe.cin.mergers.util.MergeConflict m: mergeConflicts){
								Difference diff = FPFNUtils.getOrCreateDifference(context.differences,signature,m);
								diff.types.add(Type.SAME_POSITION);
								diff.jdimeConf = m;
								diff.jdimeBody = mergedBodyContent;
								diff.signature = signature;
							}
						}
					}
				}
			}
		}
		samePstCandidates.clear();
	}

	//FPFN
	private static void processSameStmtConflicts(MergeContextSt context) {
		ASTBranchComparator comparator = new ASTBranchComparator();
		comparator.countEditionsToDifferentPartsOfSameStmt(context.branches.getBranchesFromLeft(), context.branches.getBranchesFromRight(),context);
	}
}