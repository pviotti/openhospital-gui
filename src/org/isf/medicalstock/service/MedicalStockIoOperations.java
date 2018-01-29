package org.isf.medicalstock.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Random;

import org.isf.generaldata.GeneralData;
import org.isf.generaldata.MessageBundle;
import org.isf.medicals.model.Medical;
import org.isf.medicals.service.MedicalsIoOperationRepository;
import org.isf.medicalstock.model.Lot;
import org.isf.medicalstock.model.Movement;
import org.isf.utils.db.DbQueryLogger;
import org.isf.utils.exception.OHException;
import org.isf.ward.model.Ward;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.isf.medicalstockward.model.MedicalWard;
import org.isf.medicalstockward.service.MedicalStockWardIoOperationRepository;
import org.isf.medstockmovtype.service.MedicalStockMovementTypeIoOperationRepository;

/**
 * Persistence class for MedicalStock module.
 * 		   modified by alex:
 * 			- reflection from Medicals product code
 * 			- reflection from Medicals pieces per packet
 * 			- added complete Ward and Movement construction in getMovement()
 */
@Component
@Transactional
public class MedicalStockIoOperations {

	@Autowired
	private MovementIoOperationRepository repository;
	
	@Autowired
	private LotIoOperationRepository lotRepository;
	
	@Autowired
	private MedicalsIoOperationRepository medicalRepository;

	@Autowired
	private MedicalStockWardIoOperationRepository medicalStockRepository;

	@Autowired
	private MedicalStockMovementTypeIoOperationRepository medicalStockMovementRepository;
		
	
	public enum MovementOrder {
		DATE, WARD, PHARMACEUTICAL_TYPE, TYPE;
	}

	/**
	 * Checks if we are in automatic lot mode.
	 * @return <code>true</code> if automatic lot mode, <code>false</code> otherwise.
	 */
	private boolean isAutomaticLotMode() {
		return GeneralData.AUTOMATICLOT;
	}

	/**
	 * Retrieves all medicals referencing the specified code.
	 * @param lotCode the lot code.
	 * @return the ids of medicals referencing the specified lot.
	 * @throws OHException if an error occurs retrieving the referencing medicals.
	 */
	public List<Integer> getMedicalsFromLot(
			String lotCode) throws OHException
	{
		List<Integer> medicalIds = repository.findAllByLot(lotCode);
		
		return medicalIds;
	}
	
	/**
	 * Store the specified {@link Movement} by using automatically the most old lots
	 * @param movement - the {@link Movement} to store
	 * @return <code>true</code> if the movement has been stored, <code>false</code> otherwise.
	 * @throws OHException
	 */
	public boolean newAutomaticDischargingMovement(
			Movement movement) throws OHException 
	{
		boolean result = false;
	
		
		try
		{			
			ArrayList<Lot> lots = getLotsByMedical(movement.getMedical());

			int qty = movement.getQuantity();			
			for (Lot lot : lots) 
			{
				int qtLot = lot.getQuantity();
				if (qtLot < qty) 
				{
					movement.setQuantity(qtLot);
					result = storeMovement(movement, lot.getCode());
					if (result) 
					{
						//medical stock movement inserted updates quantity of the medical
						result = updateStockQuantity(movement);
					}
					qty = qty - qtLot;
				} 
				else 
				{
					movement.setQuantity(qty);
					result = storeMovement(movement, lot.getCode());
					if (result) 
					{
						//medical stock movement inserted updates quantity of the medical
						result = updateStockQuantity(movement);
					}
					break;
				}
			}
		}
		catch (Exception e)
		{
			throw new OHException(MessageBundle.getMessage("angal.sql.problemsoccurredwiththesqlistruction"), e);			
		}
		
		return result;
	}
		
	/**
	 * Stores the specified {@link Movement}.
	 * @param movement - the movement to store.
	 * @return <code>true</code> if the movement has been stored, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the store operation.
	 */
	public boolean newMovement(
			Movement movement) throws OHException 
	{
		String lotCode = null;
		
		
		if (movement.getLot() != null)
		{
			lotCode = movement.getLot().getCode();
		}

		try 
		{
			//we have to manage the Lot
			if (movement.getType().getType().contains("+")) 
			{
				//if is in automatic lot mode then we have to generate a new lot code
				if (isAutomaticLotMode() || lotCode.equals("")) 
				{
					lotCode = generateLotCode();
				}

				boolean lotExists = lotExists(lotCode);
				if (!lotExists) 
				{
					boolean lotStored = storeLot(lotCode, movement.getLot());
					if (!lotStored) 
					{
						return false;
					}
				}
			}

			boolean movementStored = storeMovement(movement, lotCode);
			if (movementStored) 
			{
				//medical stock movement inserted updates quantity of the medical
				boolean stockQuantityUpdated = updateStockQuantity(movement);
				if (stockQuantityUpdated) 
				{
					return true;
				}
			}

			//something is failed
			return false;
		} 
		finally 
		{
			//Nothing to do
		}
	}
	
	/**
	 * Prepare the insert of the specified {@link Movement} (no commit)
	 * @param dbQuery - the session with the DB
	 * @param movement - the movement to store.
	 * @return <code>true</code> if the movement has been stored, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the store operation.
	 */
	public boolean prepareChargingMovement(
			Movement movement) throws OHException 
	{ 
		return newMovement(movement);
	}
	
	/**
	 * Prepare the insert of the specified {@link Movement} (no commit)
	 * @param dbQuery - the session with the DB
	 * @param movement - the movement to store.
	 * @return <code>true</code> if the movement has been stored, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the store operation.
	 */
	public boolean prepareDischargingwMovement(
			Movement movement) throws OHException 
	{
		String lotCode = null;
		
		
		if (movement.getLot() != null)
		{
			lotCode = movement.getLot().getCode();
		}

		boolean movementStored = storeMovement(movement, lotCode);

		//medical stock movement inserted
		if (movementStored) 
		{
			// updates quantity of the medical
			boolean stockQuantityUpdated = updateStockQuantity(movement);
			if (stockQuantityUpdated) 
			{
				return true;
			}
		}

		//something is failed
		return false;
	}

	/**
	 * Stores the specified {@link Movement}.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @param movement the movement to store.
	 * @param lotCode the {@link Lot} code to use.
	 * @return <code>true</code> if the movement has stored, <code>false</code> otherwise.
	 * @throws OHException if an error occurs storing the movement.
	 */
	protected boolean storeMovement(
			Movement movement, 
			String lotCode) throws OHException
	{
		boolean result = true;
	

		Lot lot = (Lot)lotRepository.findOne(lotCode); 
		movement.setLot(lot);
		Movement savedMovement = repository.save(movement);
		result = (savedMovement != null);
		
		return result;
	}

	/**
	 * Creates a new unique lot code.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @return the new unique code.
	 * @throws OHException if an error occurs during the code generation.
	 */
	protected String generateLotCode() throws OHException
	{
		Random random = new Random();
		long candidateCode = 0;
		Lot lot = null;
				
		
		try 
		{
			do 
			{
				candidateCode = Math.abs(random.nextLong());

				lot = (Lot)lotRepository.findOne(String.valueOf(candidateCode)); 
			} while (lot !=null);
		} 
		catch (Exception e) 
		{
			throw new OHException(MessageBundle.getMessage("angal.sql.problemsoccurredwiththesqlistruction"), e);
		} 

		return String.valueOf(candidateCode);
	}

	/**
	 * Checks if the specified {@link Lot} exists.
	 * @param dbQuery the {@link DbQueryLogger} to use for the check.
	 * @param lotCode the lot code.
	 * @return <code>true</code> if exists, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the check.
	 */
	public boolean lotExists(
			String lotCode) throws OHException
	{
		Lot lot = null;
		boolean result = false;
		
		
		try 
		{
			lot = (Lot)lotRepository.findOne(lotCode); 
			if (lot != null)
			{
				result = true;
			}
		} 
		catch (Exception e) 
		{
			throw new OHException(MessageBundle.getMessage("angal.sql.problemsoccurredwiththesqlistruction"), e);
		} 
		
		return result;
	}

	/**
	 * Stores the specified {@link Lot}.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @param lotCode the {@link Lot} code.
	 * @param lot the lot to store.
	 * @return <code>true</code> if the lot has been stored, <code>false</code> otherwise.
	 * @throws OHException if an error occurred storing the lot.
	 */
	protected boolean storeLot(
			String lotCode, 
			Lot lot) throws OHException 
	{
		boolean result = false;

		
		try 
		{
			lot.setCode(lotCode);
			lotRepository.save(lot);
			result = true;	
		} 
		catch (Exception e) 
		{
			throw new OHException(MessageBundle.getMessage("angal.sql.problemsoccurredwiththesqlistruction"), e);
		} 
		
		return result;
	}

	/**
	 * Updated {@link Medical} stock quantity for the specified {@link Movement}.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @param movement the movement.
	 * @return <code>true</code> if the quantity has been updated, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the update.
	 */
	protected boolean updateStockQuantity(
			Movement movement) throws OHException 
	{
		if (movement.getType().getType().contains("+")) 
		{
			//incoming medical stock
			int medicalCode = movement.getMedical().getCode();
			boolean updated = updateMedicalIncomingQuantity(medicalCode, movement.getQuantity());
			
			return updated;
		} 
		else 
		{
			//outgoing medical stock
			int medicalCode = movement.getMedical().getCode();
			boolean updated = updateMedicalOutcomingQuantity(medicalCode, movement.getQuantity());
			if (!updated)
			{				
				return false;
			}
			else 
			{
				Ward ward = movement.getWard();
				if (ward != null) 
				{
					//updates stock quantity for wards
					return updateMedicalWardQuantity(ward.getCode(), medicalCode, movement.getQuantity());

				} 
				else 
				{
					return true;
				}
			}
		}
	}

	/**
	 * Updates the incoming quantity for the specified medical.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @param medicalCode the medical code.
	 * @param incrementQuantity the quantity to add.
	 * @return <code>true</code> if the quantity has been updated, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the update.
	 */
	protected boolean updateMedicalIncomingQuantity(
			int medicalCode, 
			double incrementQuantity) throws OHException
	{
		boolean result = true;
				
		
		Medical medical = (Medical)medicalRepository.findOne(medicalCode); 
		medical.setInqty(medical.getInqty()+incrementQuantity);
		medicalRepository.save(medical);
		
		return result;
	}

	/**
	 * Updates the outcoming quantity for the specified medicinal.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @param medicalCode the medical code.
	 * @param incrementQuantity the quantity to add to the current outcoming quantity.
	 * @return <code>true</code> if the outcoming quantity has been updated <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the update.
	 */
	protected boolean updateMedicalOutcomingQuantity(
			int medicalCode, 
			double incrementQuantity) throws OHException
	{
		boolean result = true;
				

		Medical medical = (Medical)medicalRepository.findOne(medicalCode); 
		medical.setInqty(medical.getOutqty()+incrementQuantity);
		medicalRepository.save(medical);
				
		return result;
	}

	/**
	 * Updates medical quantity for the specified ward.
	 * @param dbQuery the {@link DbQueryLogger} to use.
	 * @param wardCode the ward code.
	 * @param medicalCode the medical code.
	 * @param quantity the quantity to add to the current medical quantity.
	 * @return <code>true</code> if the quantity has been updated/inserted, <code>false</code> otherwise.
	 * @throws OHException if an error occurs during the update.
	 */
	@SuppressWarnings("unchecked")
	protected boolean updateMedicalWardQuantity(
			String wardCode, 
			int medicalCode, 
			int quantity) throws OHException
	{
		List<MedicalWard> medicalWards = (List<MedicalWard>)medicalStockRepository.findAllWhereIds(wardCode, medicalCode);		
				
		if (!medicalWards.isEmpty())
		{			
			for (MedicalWard medicalWard : medicalWards)
			{
				medicalWard.setInQuantity(medicalWard.getInQuantity()+quantity);
				medicalStockRepository.save(medicalWard);
			}
		}
		else
		{
			MedicalWard medicalWard = new MedicalWard(wardCode.charAt(0), medicalCode, quantity, 0);
			medicalStockRepository.save(medicalWard);
		}
		
		return true;
	}

	/**
	 * Gets all the stored {@link Movement}.
	 * @return all retrieved movement
	 * @throws OHException if an error occurs retrieving the movements.
	 */
	public ArrayList<Movement> getMovements() throws OHException 
	{
		return getMovements(null, null, null);
	}

	/**
	 * Retrieves all the stored {@link Movement}s for the specified {@link Ward}.
	 * @param wardId the ward id.
	 * @param dateTo 
	 * @param dateFrom 
	 * @return the list of retrieved movements.
	 * @throws OHException if an error occurs retrieving the movements.
	 */
	public ArrayList<Movement> getMovements(
			String wardId, 
			GregorianCalendar dateFrom, 
			GregorianCalendar dateTo) throws OHException 
	{
		ArrayList<Integer> pMovementCode = null;
		ArrayList<Movement> pMovement = new ArrayList<Movement>();
		
		
		pMovementCode = new ArrayList<Integer>(repository.findtMovementWhereDatesAndId(wardId, dateFrom, dateTo));			
		for (int i=0; i<pMovementCode.size(); i++)
		{
			Integer code = pMovementCode.get(i);
			Movement movement = repository.findOne(code);
			
			
			pMovement.add(i, movement);
		}
		
		return pMovement;
	}

	/**
	 * Retrieves all the stored {@link Movement} with the specified criteria.
	 * @param medicalCode the medical code.
	 * @param medicalType the medical type.
	 * @param wardId the ward type.
	 * @param movType the movement type.
	 * @param movFrom the lower bound for the movement date range.
	 * @param movTo the upper bound for the movement date range.
	 * @param lotPrepFrom the lower bound for the lot preparation date range.
	 * @param lotPrepTo the upper bound for the lot preparation date range.
	 * @param lotDueFrom the lower bound for the lot due date range.
	 * @param lotDueTo the lower bound for the lot due date range.
	 * @return all the retrieved movements.
	 * @throws OHException
	 */
	public ArrayList<Movement> getMovements(
			Integer medicalCode,
			String medicalType, 
			String wardId, 
			String movType,
			GregorianCalendar movFrom, 
			GregorianCalendar movTo,
			GregorianCalendar lotPrepFrom, 
			GregorianCalendar lotPrepTo,
			GregorianCalendar lotDueFrom, 
			GregorianCalendar lotDueTo) throws OHException 
	{
		ArrayList<Integer> pMovementCode = null;
		ArrayList<Movement> pMovement = new ArrayList<Movement>();
		
		
		pMovementCode = new ArrayList<Integer>(repository.findtMovementWhereData(
				medicalCode, medicalType, wardId, movType, 
				movFrom, movTo, lotPrepFrom, lotPrepTo, lotDueFrom, lotDueTo));			
		for (int i=0; i<pMovementCode.size(); i++)
		{
			Integer code = pMovementCode.get(i);
			Movement movement = repository.findOne(code);
			
			
			pMovement.add(i, movement);
		}
		
		return pMovement;	
	}

	/**
	 * Retrieves {@link Movement}s for printing using specified filtering criteria.
	 * @param medicalDescription the medical description.
	 * @param medicalTypeCode the medical type code.
	 * @param wardId the ward id.
	 * @param movType the movement type.
	 * @param movFrom the lower bound for the movement date range.
	 * @param movTo the upper bound for the movement date range.
	 * @param lotCode the lot code.
	 * @param order the result order.
	 * @return the retrieved movements.
	 * @throws OHException if an error occurs retrieving the movements.
	 */
	public ArrayList<Movement> getMovementForPrint(
			String medicalDescription,
			String medicalTypeCode, 
			String wardId, 
			String movType,
			GregorianCalendar movFrom, 
			GregorianCalendar movTo, 
			String lotCode,
			MovementOrder order) throws OHException 
	{

		ArrayList<Integer> pMovementCode = null;
		ArrayList<Movement> pMovement = new ArrayList<Movement>();
		
		
		pMovementCode = new ArrayList<Integer>(repository.findtMovementForPrint(
				medicalDescription, medicalTypeCode, wardId, movType, 
				movFrom, movTo, lotCode, order));			
		for (int i=0; i<pMovementCode.size(); i++)
		{
			Integer code = pMovementCode.get(i);
			Movement movement = repository.findOne(code);
			
			
			pMovement.add(i, movement);
		}
		
		return pMovement;	
	}

	/**
	 * Retrieves lot referred to the specified {@link Medical}.
	 * @param medical the medical.
	 * @return a list of {@link Lot}.
	 * @throws Exception 
	 */
	public ArrayList<Lot> getLotsByMedical(
			Medical medical) throws Exception 
	{
		ArrayList<Lot> lots = null;
	
		
		List<Object[]> lotList = (List<Object[]>)lotRepository.findAllWhereMedical(medical.getCode());
		lots = new ArrayList<Lot>();
		for (Object[] object: lotList)
		{
			Lot lot = _convertObjectToLot(object);
			
			lots.add(lot);
		}
		
		return lots;
	}	

	private Lot _convertObjectToLot(Object[] object)
	{

		Lot lot = new Lot();
		lot.setCode((String)object[0]);
		lot.setPreparationDate(_convertTimestampToCalendar((Timestamp)object[1]));
		lot.setDueDate(_convertTimestampToCalendar((Timestamp)object[2]));
		lot.setCost((Double)object[3]);
		lot.setQuantity(((Double)object[4]).intValue());
		
		return lot;
	}
	
	private GregorianCalendar _convertTimestampToCalendar(Timestamp time)
	{
		GregorianCalendar calendar = null;
		
		if (time != null) 
		{
			calendar = new GregorianCalendar();
			calendar.setTimeInMillis(time.getTime());
		}
		
		return calendar;
	}
		
	/**
	 * returns the date of the last movement
	 * @return 
	 * @throws OHException
	 */
	public GregorianCalendar getLastMovementDate() throws OHException 
	{
		GregorianCalendar gc = new GregorianCalendar();
				
			
		Timestamp time = (Timestamp)medicalStockMovementRepository.findMaxDate();
		if (time != null) 
		{
			gc.setTime(time);
		}
		else
		{
			gc = null;
		}					
	
		return gc;
	}
	
	/**
	 * check if the reference number is already used
	 * @return <code>true</code> if is already used, <code>false</code> otherwise.
	 * @throws OHException
	 */
	public boolean refNoExists(
			String refNo) throws OHException 
	{
		boolean result = false;
		
			
		if (medicalStockMovementRepository.findAllWhereRefNo(refNo).size() > 0)
		{
			result = true;
		}		
			
		return result;
	}

	/**
	 * Retrieves all the movement associated to the specified reference number.
	 * In case of error a message error is shown and a <code>null</code> value is returned.
	 * @param refNo the reference number.
	 * @return the retrieved movements.
	 * @throws OHException 
	 */
	public ArrayList<Movement> getMovementsByReference(
			String refNo) throws OHException 
	{
		ArrayList<Movement> movements = (ArrayList<Movement>) repository.findAllByRefNo(refNo);
						
		
		return movements;
	}
}
