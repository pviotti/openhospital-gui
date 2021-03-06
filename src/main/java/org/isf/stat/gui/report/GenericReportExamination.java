package org.isf.stat.gui.report;

import javax.swing.JOptionPane;

import org.isf.generaldata.GeneralData;
import org.isf.generaldata.MessageBundle;
import org.isf.menu.manager.Context;
import org.isf.stat.dto.JasperReportResultDto;
import org.isf.stat.manager.JasperReportsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jasperreports.view.JasperViewer;

public class GenericReportExamination {
	private final Logger logger = LoggerFactory.getLogger(GenericReportPatient.class);
	private JasperReportsManager jasperReportsManager = Context.getApplicationContext()
			.getBean(JasperReportsManager.class);

	public GenericReportExamination(Integer patientID, Integer examId, String jasperFileName) {
		try {
			JasperReportResultDto jasperReportResultDto = jasperReportsManager
					.getGenericReportPatientExaminationPdf(patientID, examId, jasperFileName);
			if (GeneralData.INTERNALVIEWER)
				JasperViewer.viewReport(jasperReportResultDto.getJasperPrint(), false);
			else {
				Runtime rt = Runtime.getRuntime();
				rt.exec(GeneralData.VIEWER + " " + jasperReportResultDto.getFilename());
			}
		} catch (Exception e) {
			logger.error("", e);
			JOptionPane.showMessageDialog(null, MessageBundle.getMessage("angal.stat.reporterror"),
					MessageBundle.getMessage("angal.hospital"), JOptionPane.ERROR_MESSAGE);
		}
	}
}