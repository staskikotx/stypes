package uk.ac.bbk.dcs.stypes

import fr.lirmm.graphik.graal.api.core.{Substitution, _}
import fr.lirmm.graphik.graal.core.TreeMapSubstitution
import fr.lirmm.graphik.graal.core.atomset.LinkedListAtomSet
import fr.lirmm.graphik.graal.core.factory.ConjunctiveQueryFactory
import fr.lirmm.graphik.graal.homomorphism.StaticHomomorphism

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
  * Created by
  *   Salvatore Rapisarda
  *   Stanislav Kikot
  *
  * on 24/06/2017.
  *
  */
case class TypeExtender(bag: Bag, hom: Substitution, canonicalModels: Array[AtomSet],
                   atomsToBeMapped: List[Atom], variableToBeMapped:List[Term] ) {

  def this(bag: Bag, hom: Substitution, canonicalModels: Array[AtomSet] ) =
    this(bag, hom, canonicalModels, bag.atoms.toList, bag.variables.filter( p=> ! hom.getTerms.contains(p) ).toList )

  private val typesExtended =  getTypesExtension
  val children: List[TypeExtender] = typesExtended._2
  val isValid:Boolean = typesExtended._1
  val types: List[Type] = collectTypes( children )

  type AtomSetWithCanonicalModelIndex = (AtomSet, Int)

  private def getAtomSetWithCanonicalModelIndex(atom: Atom): Option[AtomSetWithCanonicalModelIndex] = {
    val intersection: Set[Term] = getKnownVariables(atom)
    if (intersection.nonEmpty) {
      val term: Option[Term] = intersection.find(p => !hom.createImageOf(p).equals(ConstantType.EPSILON))
      if (term.isDefined) {
        val canonicalModelIndex = hom.createImageOf(term.get).asInstanceOf[ConstantType].getIdentifier._1
        val cm: AtomSet = canonicalModels(hom.createImageOf(term.get).asInstanceOf[ConstantType].getIdentifier._1)
        Some(cm, canonicalModelIndex)
      } else None
    } else None
  }

  private def getKnownVariables(atom: Atom): Set[Term] = {
    val terms: Set[Term] = atom.getTerms.asScala.toSet
    val homTerms = hom.getTerms.asScala.toSet
    terms.intersect(homTerms)
  }

  private def getUnknownVariables(atom: Atom): Set[Term] = {
    val terms: Set[Term] = atom.getTerms.asScala.toSet
    val knownVariables = getKnownVariables(atom)

    if (knownVariables.isEmpty)
      terms
    else
      terms.filter(!knownVariables.contains(_))

  }

  /**
    * Collect homomorphisms from valid leaves
    * @param children to visit
    * @return a List of Type
    */
  def collectTypes( children: List[TypeExtender]) : List[Type] = {

    @tailrec
    def collectTypesH(children: List[TypeExtender], acc: List[Type]): List[Type] = children match {
      case List() => acc
      case x :: xs =>
        if (x.children.isEmpty  ) collectTypesH(xs, Type(x.hom) :: acc)
        else collectTypesH(xs, acc:::collectTypes(xs) )
    }

    collectTypesH(children, List())

  }

  private def isGoodWithRespectToSubstitution(substitution: Substitution, atom: Atom): Boolean = {
    def isBadTerm(term: Term) = {
      val homTerm: Term = hom.createImageOf(term)
      val subTerm = substitution.createImageOf(term)
      (!(subTerm.getLabel.equals(homTerm.getLabel) || homTerm.equals(ConstantType.EPSILON))
        || homTerm.equals(ConstantType.EPSILON) && !ReWriter.isAnonymous(subTerm))
    }

    val knownVariables: Set[Term] = getKnownVariables(atom)
    val collection = knownVariables.filter(isBadTerm)
    collection.isEmpty
  }

  private def extend( atom: Atom,  answers: List[Substitution], canonicalModelIndex:Int, atoms: List[Atom] ): List[TypeExtender] = {
     @tailrec
    def extendH( answers: List[Substitution], acc: List[TypeExtender]): List[TypeExtender] = answers match {
      case List() => acc
      case x::xs =>
        val substitution= extendSubstitution( atom, x,canonicalModelIndex )
        val unknownVariables=  getUnknownVariables(atom)
        val t = TypeExtender( bag, substitution, canonicalModels, atoms, variableToBeMapped.filter( v => ! unknownVariables.contains(v) ) )
        extendH(xs, t::acc)

    }
    extendH(answers, List())
  }

  private def extendSubstitution (atom:Atom, answer :Substitution, canonicalModelIndex: Int):Substitution ={

    def extendVariable(term:Term, answer :Substitution, canonicalModelIndex: Int, modifiedHom: Substitution):Substitution = {
      if (ReWriter.isAnonymous( answer.createImageOf( term) ) ) {
        modifiedHom.put( term, new  ConstantType( (canonicalModelIndex, answer.createImageOf(term).getLabel ) )  )
      }else{
        modifiedHom.put( term, ConstantType.EPSILON )
      }
      modifiedHom
    }

    @tailrec
    def extendToTheSetOfVariables ( terms:List[Term], modifiedHom: Substitution  ) :Substitution = terms match {
      case List() =>  modifiedHom
      case  x :: xs => extendToTheSetOfVariables( xs,  extendVariable ( x, answer, canonicalModelIndex, modifiedHom ) )
    }

    val modifiedHom: Substitution  = new TreeMapSubstitution(hom)
    val unknownVariables: List[Term] = getUnknownVariables(atom).toList
    extendToTheSetOfVariables(unknownVariables, modifiedHom  )

  }

  private def  getTypesExtension: (Boolean, List[TypeExtender]) =   {

    @tailrec
    def getPossibleConnectedTypesExtensions(atoms: List[Atom], acc: (Boolean,  List[TypeExtender] ) ) :
    ( Boolean,List[TypeExtender]) = atoms match {
      case List() => acc
      case x :: xs  =>
        // getting the tuple of atomSet and CanonicalModelIndex
        val atomSetAndCanModIndex: Option[AtomSetWithCanonicalModelIndex] = getAtomSetWithCanonicalModelIndex(x)
        // If the atom set is defined then the atom is connected
        if (atomSetAndCanModIndex.isDefined) {
          val cq = ConjunctiveQueryFactory.instance.create(new LinkedListAtomSet(x))
          val result: List[Substitution] = StaticHomomorphism.instance.execute(cq, atomSetAndCanModIndex.get._1).asScala.toList
          val goodSubstitutions: List[Substitution] = result.filter( s => isGoodWithRespectToSubstitution( s, x))
          val extension = extend(x, goodSubstitutions, atomSetAndCanModIndex.get._2, atomsToBeMapped.tail)
          getPossibleConnectedTypesExtensions(List(),  ( true, acc._2:::extension))
        }else
          getPossibleConnectedTypesExtensions(xs, acc)

    }

    def extendToATerm( variable:Term ) : List[TypeExtender] = {
      //
      def extend( canonicalModelIndex: Int, maxIndex:Int,  acc:List[TypeExtender] ) : List[TypeExtender] = {
        //
        @tailrec
        def extenderInsideM( terms:List[Term], acc:List[TypeExtender]  ) :List[TypeExtender]  =  terms match {
          case List() => acc
          case head::tail =>
            if ( ReWriter.isAnonymous(head) ) extenderInsideM(tail, buildExtender( new ConstantType(canonicalModelIndex,  head.getLabel))::acc)
            else extenderInsideM(tail, acc )
        }

        //
        if  ( maxIndex == canonicalModelIndex)  acc
        else {
          val extendedTerms =   extenderInsideM( canonicalModels(canonicalModelIndex).getTerms.asScala.toList, List() )
          extend(canonicalModelIndex + 1, maxIndex, extendedTerms:::acc  )
        }
      }

      def buildExtender( term: ConstantType ) : TypeExtender = {
        val h = new TreeMapSubstitution(hom)
        h.put(variable, term)
        TypeExtender(bag, h, canonicalModels, atomsToBeMapped, variableToBeMapped.tail )
      }

      extend( 0, canonicalModels.length,  List(buildExtender(ConstantType.EPSILON)))
    }

    def filterThroughAtoms(atoms:List[Atom]): Boolean = atoms match  {
      case List() => true
      case x::xs =>
        if (isGoodRespectToCanonicalModel(x))
          filterThroughAtoms(xs)
        else
          false

    }

    def isGoodRespectToCanonicalModel(atom: Atom): Boolean = {

      def areAllEqualCanonicalModelIndex(canonicalModelIndex:Int, terms:Seq[Term]): Boolean = terms match {
        case Nil=> true
        case x::xs =>
          if ( x.asInstanceOf[ConstantType].identifier._1==canonicalModelIndex)
            areAllEqualCanonicalModelIndex(canonicalModelIndex, xs )
          else false

      }

      val notEpsilon = atom.getTerms.asScala.map(hom.createImageOf ).filter(t => ! t.equals(ConstantType.EPSILON))
      if ( notEpsilon.isEmpty)
        true
      else {
        val canonicalModelIndex =  notEpsilon.head.asInstanceOf[ConstantType].identifier._1
        val res = areAllEqualCanonicalModelIndex(canonicalModelIndex, notEpsilon.tail)
        if ( !res) false
        else {
          val cm = canonicalModels(canonicalModelIndex)
          val generatingTerms = cm.getTerms().asScala.filter( p=> ! ReWriter.isAnonymous( p ) )
          val s = new TreeMapSubstitution()
          generatingTerms.foreach( p => s.put(p, ConstantType.EPSILON ) )
          val image =  s.createImageOf(cm)
          image.contains(atom)
        }

      }

    }

    if (variableToBeMapped.isEmpty)
      (filterThroughAtoms(atomsToBeMapped), List())
    else {
      val possibleConnectedExtensions = getPossibleConnectedTypesExtensions(atomsToBeMapped, (false, List()))

      if (possibleConnectedExtensions._1)
        (true, possibleConnectedExtensions._2)
      else
        (true, extendToATerm(variableToBeMapped.head))
    }

  }

}
