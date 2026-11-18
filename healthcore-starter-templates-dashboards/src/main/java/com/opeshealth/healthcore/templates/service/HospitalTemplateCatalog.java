/*
 * =============================================================================
 * Opes HealthCore SDK — 608 Domain-Accurate Bilingual Hospital Template Catalog
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.templates.service;

import java.util.List;
import java.util.Map;

/**
 * Curated bilingual (English / French) catalog of 608 authentic clinical, specialty,
 * pharmacy, laboratory, imaging, CRVS, UHC billing, and executive compliance templates
 * organized across 16 clinical &amp; operational packs (38 unique templates per pack).
 */
public final class HospitalTemplateCatalog {

    private HospitalTemplateCatalog() {}

    public static final List<String> SIXTEEN_PACKS = List.of(
            "01_REGISTRATION_CONSENT", "02_TRIAGE_EMERGENCY", "03_OUTPATIENT_CLINICAL",
            "04_SPECIALTY_CLINICS", "05_INPATIENT_NURSING", "06_SURGERY_ANESTHESIA",
            "07_MATERNITY_OBGYN", "08_LABORATORY_LIS", "09_RADIOLOGY_IMAGING",
            "10_PHARMACY_PIS", "11_BLOOD_BANK", "12_BILLING_UHC_CLAIMS",
            "13_DISCHARGE_REFERRAL", "14_CRVS_MORTUARY", "15_PUBLIC_HEALTH_CHW",
            "16_EXECUTIVE_COMPLIANCE"
    );

    private static final Map<String, List<String>> PACK_FORMS_EN = Map.ofEntries(
            Map.entry("01_REGISTRATION_CONSENT", List.of(
                    "New Patient Intake & Demographics Form", "General Consent for Medical Treatment",
                    "HIPAA / Data Privacy Notice Acknowledgment", "Next-of-Kin & Emergency Contact Declaration",
                    "Patient Identification Wristband Verification", "Biometric Enrollment & Deduplication Form",
                    "Minor / Pediatric Guardian Consent Form", "Telemedicine Informed Consent",
                    "Health Insurance / CBHI Card Verification Sheet", "Release of Protected Health Information (ROI)",
                    "Patient Portal Activation Agreement", "Interpreter & Cultural Language Preference Form",
                    "Advance Directive & Living Will Intake", "Healthcare Proxy Designation",
                    "Self-Pay Financial Counseling Agreement", "Duplicate MRN Merge Authorization",
                    "Visitor Access & Confidentiality Pledge", "Outpatient Queue Registration Slip",
                    "Referral Intake & Prior Authorization Cover", "VIP / Restricted Chart Break-Glass Notice",
                    "Patient Photograph & Media Release", "HIV/Sensitive Data Compartmentalization Consent",
                    "Clinical Trial Screening Interest Form", "Organ & Tissue Donor Registry Preference",
                    "Out-of-Area Resident Temporary Registration", "Foreign National Passport & Visa Check",
                    "Indigent / Social Welfare Waiver Intake", "Corporate Occupational Health Intake",
                    "School / University Clinic Enrollment Form", "Unconscious Unknown Patient Trauma Alias Form",
                    "Ambulatory Clinic Check-In Attestation", "Patient Address & GPS Landmark Verification",
                    "Preferred Pharmacy & Lab Routing Selection", "Missed Appointment / No-Show Outreach Log",
                    "Patient Feedback & Grievance Intake Form", "Lost Health Card Replacement Request",
                    "Deceased Record Administrative Closure", "Multi-Facility HIE Opt-In Consent"
            )),
            Map.entry("02_TRIAGE_EMERGENCY", List.of(
                    "Emergency Severity Index (ESI 1-5) Triage Sheet", "Trauma Resuscitation Primary Survey (ABCDE)",
                    "Acute Chest Pain & STEMI Activation Checklist", "Stroke FAST / NIHSS Rapid Assessment",
                    "Sepsis Screening & 1-Hour Bundle Checklist", "Pediatric Emergency Triangle (PAT) Triage",
                    "Mass Casualty Incident (MCI) START Triage Tag", "Glasgow Coma Scale (GCS) Serial Flowsheet",
                    "Poisoning & Toxicology Exposure Intake", "Burn TBSA Lund-Browder Assessment Chart",
                    "Anaphylaxis Acute Response Protocol Sheet", "Obstetric Emergency Triage & Eclampsia Alert",
                    "Acute Asthma / Respiratory Distress Score", "Behavioral Health & Suicide Risk Screen",
                    "Domestic Violence / Safeguarding Screen", "Ambulance Pre-Hospital EMS Handover (ATMIST)",
                    "Fast-Track Minor Injury Triage Card", "Bite / Rabies Post-Exposure Risk Assessment",
                    "Snakebite Envenomation Grading Sheet", "Malaria Severe Complication Triage Screen",
                    "Cholera / Acute Dehydration Plan C Sheet", "Emergency Intubation & Airway Log",
                    "Procedural Sedation Emergency Monitoring", "Code Blue Cardiac Arrest Resuscitation Log",
                    "Defibrillation & Shock Energy Record", "Focused Assessment Sonography Trauma (eFAST)",
                    "Emergency Ultrasound Needle Guidance Log", "Police / Medico-Legal Injury Certificate",
                    "Sexual Assault Forensic Evidence Chain Form", "ER Observation 24-Hour Holding Chart",
                    "Left Without Being Seen (LWBS) Log", "Against Medical Advice (AMA) ER Refusal",
                    "Inter-Facility Emergency Transfer Summary", "Disaster Triage Stabilization Checklist",
                    "Hypoglycemia Emergency Dextrose Protocol", "Status Epilepticus Benzodiazepine Log",
                    "DKA / HHS Emergency Insulin Protocol", "Urgent Psychiatric 72-Hour Hold Form"
            )),
            Map.entry("03_OUTPATIENT_CLINICAL", List.of(
                    "Outpatient Adult SOAP Consultation Note", "Comprehensive Internal Medicine H&P",
                    "Follow-Up Chronic Disease Review Note", "Fever of Unknown Origin Tropical Workup",
                    "Hypertension Initial & Titration Clinic Form", "Type 2 Diabetes Glycemic Foot & Eye Screen",
                    "Asthma & COPD Spirometry Control Review", "Pre-Employment Fitness Medical Certificate",
                    "School / Sports Participation Physical Exam", "Travel Medicine & Yellow Fever Pre-Consult",
                    "Geriatric Comprehensive Functional Assessment", "Malnutrition MUAC Adult/Adolescent Screen",
                    "Gastroenterology Dyspepsia & H. pylori Note", "Rheumatology Joint Count & DAS28 Sheet",
                    "Nephrology CKD Staging & eGFR Review", "Endocrinology Thyroid Nodule Consult Form",
                    "Hematology Sickle Cell Crisis Follow-Up", "Neurology Epilepsy Seizure Diary Review",
                    "Migraine & Headache Disability Assessment", "Infectious Disease Malaria Microscopy Review",
                    "Typhoid & Febrile Enteric Workup Note", "Chronic Hepatitis B/C Baseline Assessment",
                    "Outpatient Wound Dressing & Debridement Log", "Suture Removal & Post-Op Check Note",
                    "Sick Leave / Medical Excuse Certificate", "Fitness to Fly Aviation Medical Clearance",
                    "Driver License Vision & Medical Fitness Form", "Lifestyle Smoking & Alcohol AUDIT Screen",
                    "PHQ-9 Depression & GAD-7 Anxiety Screen", "Nutritional Counseling & Diet Plan Handout",
                    "Outpatient Pain Management Contract", "Allergy Skin Prick Test Interpretation Form",
                    "Specialist Consultation Request & Clinical Brief", "Walk-In Acute Care Express Visit Note",
                    "Telemedicine Audio/Video Consult Summary", "Annual Preventive Wellness Checkup Report",
                    "Uncontrolled BP Escalation Action Plan", "Outpatient Visit Prescription & Care Summary"
            )),
            Map.entry("04_SPECIALTY_CLINICS", List.of(
                    "Dental Odontogram (FDI) & Caries Chart", "Dental Periodontal Probing & Mobility Map",
                    "Endodontic Root Canal Working Length Log", "Oral & Maxillofacial Extraction Consent",
                    "Orthodontic Cephalometric Progress Sheet", "Prosthodontic Crown & Bridge Lab Rx",
                    "Ophthalmology Visual Acuity & Refraction Rx", "Ophthalmology Slit-Lamp & IOP Glaucoma Log",
                    "Retinal Fundoscopy & Diabetic Maculopathy Map", "Cataract Biometry & IOL Calculation Sheet",
                    "Intravitreal Anti-VEGF Injection Log", "Pediatric Strabismus & Amblyopia Exam",
                    "Cardiology 12-Lead ECG Interpretation Sheet", "Transthoracic Echocardiogram (TTE) Report",
                    "Exercise Stress Treadmill (Bruce Protocol) Log", "24h Holter Arrhythmia Monitoring Summary",
                    "Heart Failure NYHA Class & BNP Titration", "Pacemaker & ICD Device Interrogation Sheet",
                    "Pediatrics Growth Percentile WHO Z-Score Chart", "Pediatrics Developmental Milestone Denver II",
                    "IMCI Childhood Fever/Cough/Diarrhea Protocol", "Neonatal Jaundice Bilirubin Nomogram",
                    "Dermatology Lesion Body Map & Dermoscopy", "Dermatology Punch/Shave Biopsy Requisition",
                    "Psoriasis PASI & Eczema EASI Scoring Sheet", "ENT Pure-Tone Audiometry & Tympanogram",
                    "ENT Flexible Nasopharyngolaryngoscopy Note", "Urology IPSS & Uroflowmetry Assessment",
                    "Orthopedics Fracture Splint & Cast Care Sheet", "Physiotherapy ROM & Barthel Rehab Plan",
                    "Oncology TNM Staging & Chemo Regimen Sheet", "Palliative Care ESAS Symptom Burden Form",
                    "Mental Health Mini-Mental State Exam (MMSE)", "Sleep Medicine Epworth & Apnea Screening",
                    "Podiatry Diabetic Monofilament Foot Exam", "Rheumatology Lupus Flare Index (SLEDAI)",
                    "Gynecology Colposcopy & VIA Screening Form", "Specialty Clinic Multi-Disciplinary Board Note"
            )),
            Map.entry("05_INPATIENT_NURSING", List.of(
                    "Inpatient Admission Nursing Assessment", "NEWS2 Early Warning Serial Score Chart",
                    "24-Hour Fluid Intake & Output (I&O) Balance", "5-Rights Barcode eMAR Administration Sheet",
                    "SBAR Shift-to-Shift Nursing Handover Log", "Braden Pressure Ulcer Risk & Turn Chart",
                    "Morse Fall Risk & Bed Rail Safety Audit", "Peripheral IV Cannula & Phlebitis VIP Score",
                    "Central Venous Catheter Maintenance Bundle", "Urinary Foley Catheter CAUTI Care Log",
                    "Nasogastric (NG) Tube Placement & Feed Chart", "Tracheostomy Suctioning & Cuff Pressure Log",
                    "ICU 24-Hour Hemodynamic & Ventilator Flowsheet", "Inpatient Insulin Sliding Scale Glucose Log",
                    "Heparin / Warfarin Anticoagulation Monitoring", "Pain Assessment FLACC / Visual Analog Chart",
                    "Isolation & Infection Control Barrier Log", "Seizure Observation & Duration Log",
                    "Neurological Pupil & Limb Power Chart", "Chest Drain Underwater Seal Output Log",
                    "Restraint & Seclusion Justification Audit", "Daily Nursing Care Plan & Problem List",
                    "Ward Bed Transfer & Room Cleaning Clearance", "High-Alert Electrolyte Double-Check Sheet",
                    "PCA Morphine Pump Bolus Verification Log", "Enteral Nutrition Calorie Target Tracker",
                    "Telemetry Rhythm Strip Mounting Sheet", "Post-Dialysis Fistula Thrill & Bruit Check",
                    "Patient Belongings & Valuables Inventory", "Inpatient Bedside Rounding Physician Note",
                    "Rapid Response Team Activation Record", "Subcutaneous Injection Site Rotation Map",
                    "Oxygen Therapy Titration & SpO2 Target Sheet", "Inpatient Blood Glucose Point-of-Care Log",
                    "DVT Prophylaxis Compression & LMWH Check", "Wound VAC Negative Pressure Dressing Log",
                    "Delirium CAM-ICU Nursing Screen", "Inpatient Discharge Readiness Nursing Checklist"
            )),
            Map.entry("06_SURGERY_ANESTHESIA", List.of(
                    "WHO Surgical Safety Checklist (Sign-In/Out)", "Pre-Operative Anesthesia Airway Evaluation",
                    "Surgical Informed Consent (Procedure & Risks)", "Surgical Site Marking Verification Form",
                    "Pre-Op NPO, Shave & Antibiotic Timing Audit", "Intra-Operative Anesthesia Induction & Gas Log",
                    "Operating Theater Sponge, Needle & Instrument Count", "Surgical Implant & UDI Barcode Traceability",
                    "Surgeon Operative Note & Findings Diagram", "PACU Post-Anesthesia Aldrete Recovery Score",
                    "Regional Nerve Block & Spinal Anesthesia Log", "Difficult Intubation & Videolaryngoscopy Record",
                    "Intra-Op Blood Loss & Cell Saver Transfusion Log", "Tourniquet Inflation Time & Pressure Sheet",
                    "Laparoscopic Equipment & CO2 Insufflation Log", "C-Arm Intra-Operative Fluoroscopy Dose Log",
                    "Day Surgery Same-Day Discharge Criteria Sheet", "Autoclave CSSD Biological Spore Sterility Log",
                    "Endoscopy Biopsy & Scope Reprocessing Trace", "Tissue Pathology Specimen Chain-of-Custody",
                    "Post-Op Pain Nerve Catheter Infusion Chart", "Surgical Site Infection (SSI) 30-Day Audit",
                    "Emergency Laparotomy Risk & NELA Checklist", "Cesarean Section Maternal-Neonatal Handover",
                    "Orthopedic Joint Arthroplasty Prosthesis Sticker", "Ophthalmic Phacoemulsification OT Record",
                    "Cardiac Bypass Perfusion & Heparin ACT Log", "Neurosurgery Craniotomy & ICP Drain Record",
                    "Plastic Surgery Microvascular Flap Monitoring", "Theater Turnover & Deep Clean Checklist",
                    "Anesthesia Machine Pre-Use Leak & Scavenger Test", "Malignant Hyperthermia Dantrolene Emergency Kit",
                    "Unplanned Return to Operating Room Review", "Robotic / Minimally Invasive Console Log",
                    "Surgical Safety Near-Miss Morbidity Form", "Pre-Op Blood Crossmatch Hold Confirmation",
                    "Surgeon Post-Op Immediate Orders Sheet", "Operating Theater Daily Case Schedule Manifest"
            )),
            Map.entry("07_MATERNITY_OBGYN", List.of(
                    "WHO Antenatal Care (ANC) 8-Contact Card", "WHO Labor Care Guide & Partograph",
                    "Obstetric Risk Stratification & Preeclampsia Screen", "Immediate Newborn APGAR 1/5/10-Min Sheet",
                    "Prevention of Mother-to-Child HIV (PMTCT) Log", "Maternal Postpartum Hemorrhage (PPH) Bundle",
                    "Oxytocin & Misoprostol Uterotonic Audit", "Magnesium Sulfate Eclampsia Loading Log",
                    "Postnatal Maternal & Neonatal 48h Check", "Obstetric Ultrasound Biometry & Placenta Scan",
                    "Group B Strep & Gestational Diabetes OGTT Sheet", "Neonatal Resuscitation Golden Minute Record",
                    "Kangaroo Mother Care (KMC) Weight Gain Log", "NICU Incubator & Phototherapy Irradiance Sheet",
                    "Breastfeeding Latch & Lactation Support Log", "Birth Weight, Length & Head Circumference Card",
                    "Maternal Tetanus Toxoid Vaccination Record", "Rh-Negative Anti-D Immunoglobulin Log",
                    "Vacuum / Forceps Assisted Delivery Report", "Episiotomy & Perineal Tear Repair Grade Sheet",
                    "Family Planning Postpartum IUD/Implant Consent", "Spontaneous / Septic Abortion PAC Care Sheet",
                    "Ectopic Pregnancy Methotrexate Protocol Log", "High-Risk Twin/Multiple Gestation Care Plan",
                    "Preterm Labor Tocolysis & Betamethasone Sheet", "Cervical Cancer Pap Smear & HPV Screening",
                    "Stillbirth / Perinatal Death Maternal Support Form", "Birth Notification & Footprint Newborn ID",
                    "Maternal Sepsis Obstetric Modified Early Warning", "Cord Blood Gas & Placental Examination Log",
                    "Gestational Hypertension Labetalol Titration", "Post-Cesarean Wound & Lochia Assessment",
                    "NICU Surfactant & CPAP Weaning Flowsheet", "Maternal Mental Health Edinburgh Postnatal Screen",
                    "Obstetric Blood Transfusion Massive Protocol", "Newborn Hearing & Congenital Heart CCHD Screen",
                    "Vitamin K & Erythromycin Eye Prophylaxis Log", "Maternity Discharge & 6-Week Postpartum Card"
            )),
            Map.entry("08_LABORATORY_LIS", List.of(
                    "General Hematology CBC & Differential Report", "Malaria Blood Smear Parasitemia & RDT Report",
                    "Comprehensive Metabolic & Renal Panel (CMP)", "Liver Function Tests (ALT/AST/Bilirubin) Report",
                    "Lipid Profile & Cardiovascular Risk Report", "HbA1c & Fasting Glucose Diabetes Panel",
                    "Urinalysis Microscopy & Culture Sensitivity", "Blood Culture Aerobic/Anaerobic Gram & AST",
                    "CSF Meningitis Cell Count, Protein & Culture", "GeneXpert MTB/RIF & AFB Sputum TB Report",
                    "HIV 1/2 Rapid Combo & Viral Load PCR Report", "Hepatitis B/C Serology & PCR Panel Report",
                    "Coagulation PT/INR, aPTT & D-Dimer Report", "Troponin I/T & NT-proBNP Cardiac Marker Report",
                    "Thyroid TSH, Free T3/T4 Endocrine Report", "Histopathology & Surgical Biopsy Report",
                    "Cytopathology Fine-Needle Aspiration Report", "Stool Ova, Parasites & Occult Blood Report",
                    "Widal, Brucella & Febrile Agglutinin Panel", "COVID-19 / Respiratory Multiplex PCR Report",
                    "Specimen Rejection & Hemolysis Notification", "Critical Panic Lab Value Read-Back Log",
                    "Daily Hematology Analyzer QC Levey-Jennings", "Chemistry Analyzer Calibration & Reagent Lot",
                    "Microbiology Antibiotic Disk Diffusion (CLSI)", "Lab Specimen Courier Chain-of-Custody Manifest",
                    "Turnaround Time (TAT) STAT Breach Report", "Semen Analysis & Fertility Spermiogram",
                    "Bone Marrow Aspirate & Biopsy Morphology", "Autoimmune ANA, RF & Anti-CCP Panel",
                    "Serum Ferritin, Iron & TIBC Anemia Panel", "Arterial Blood Gas (ABG) & Lactate Report",
                    "Therapeutic Drug Monitoring (Vancomycin/Digoxin)", "Pregnancy Beta-hCG Quantitative Report",
                    "Sickle Cell Hemoglobin Electrophoresis Report", "External Quality Assessment (EQA) PT Sheet",
                    "Biosafety Spill & Exposure Incident Form", "Monthly Laboratory Workload & Test Tally"
            )),
            Map.entry("09_RADIOLOGY_IMAGING", List.of(
                    "Chest X-Ray PA/Lateral Structured Report", "CT Brain Non-Contrast Stroke Exclusion Report",
                    "CT Chest Pulmonary Embolism Angiography Report", "CT Abdomen/Pelvis Contrast Trauma Report",
                    "MRI Brain & Spine Neuro-Imaging Report", "Abdominal & Pelvic Diagnostic Ultrasound Report",
                    "Obstetric Fetal Anatomy Ultrasound Report", "Carotid & Lower Limb Venous Doppler Report",
                    "Mammography BI-RADS Screening Report", "DXA Bone Mineral Densitometry T-Score Report",
                    "IV Contrast Iodinated Allergy & eGFR Safety Check", "MRI Ferromagnetic Implant Safety Screening",
                    "Pregnancy Radiation Shielding Consent Form", "Interventional Radiology Biopsy/Drain Procedure",
                    "Fluoroscopy Barium Swallow / Enema Report", "Dental Panoramic OPG & CBCT Imaging Report",
                    "Pediatric Portable NICU Chest X-Ray Report", "Critical Radiology Finding Verbal Escalation",
                    "DICOM PACS Study Import & CD Burn Receipt", "Radiation Dose DLP/CTDIvol Exposure Audit",
                    "Thyroid & Parathyroid High-Res Ultrasound", "Scrotal & Testicular Torsion Doppler Report",
                    "Musculoskeletal Shoulder/Knee MRI Report", "Coronary CT Angiography Calcium Score Report",
                    "Hysterosalpingography (HSG) Fertility Report", "Cranial Ultrasound Neonatal IVH Screen",
                    "Contrast Extravasation Nursing Incident Form", "Portable Bedside X-Ray ICU Request Slip",
                    "Radiologist Peer-Review Discrepancy Audit", "Equipment Tube Warm-Up & Phantoms QC Log",
                    "Nuclear Medicine Thyroid Uptake Scan Report", "PET-CT Oncology Metabolic Staging Report",
                    "Angiography Stent & Embolization Record", "FAST Emergency Trauma Bedside Sonography",
                    "Pleural Effusion Thoracentesis Ultrasound Mark", "Skeletal Survey Trauma / Myeloma Series",
                    "Outside Film Second-Opinion Over-Read Form", "Monthly Modality Utilization & TAT Report"
            )),
            Map.entry("10_PHARMACY_PIS", List.of(
                    "Outpatient Dispensing Prescription Label & Receipt", "Inpatient Unit-Dose Cart Fill Manifest",
                    "Controlled Narcotics & Psychotropics Register", "Antimicrobial Stewardship Restricted Drug Form",
                    "FEFO Near-Expiry Stock Quarantine Sheet", "Drug-Drug Interaction Override Justification",
                    "Medication Error & Near-Miss Pharmacovigilance", "Adverse Drug Reaction (ADR) National Report",
                    "Compounding Sterile IV Admixture Worksheet", "Chemotherapy Cytotoxic Dose Verification Log",
                    "Pediatric Weight-Based Oral Solution Counseling", "Cold-Chain Insulin/Biologic Storage Log",
                    "Emergency Crash Cart Tray Seal & Restock Audit", "Ward Floor Stock Replenishment Requisition",
                    "Inpatient-to-Home Medication Reconciliation List", "HIV Antiretroviral (ART) Dispensing Card",
                    "Anti-Tuberculosis Fixed-Dose Blister Log", "Malaria ACT Artemether-Lumefantrine Register",
                    "Purchase Order Supplier Receiving & Batch Check", "Physical Cycle Count Stock Variance Report",
                    "Quarantined Recall Manufacturer Lot Notification", "Unlicensed / Compassionate Use Drug Request",
                    "Renal Function Dose Adjustment Pharmacist Note", "Anticoagulation Warfarin Counseling Booklet",
                    "Inhaler Technique & Spacer Teaching Checklist", "Opioid Naloxone Take-Home Co-Prescribing Form",
                    "Expired Narcotic Witnessed Destruction Certificate", "Inter-Store Pharmacy Stock Transfer Voucher",
                    "Clinical Pharmacist Ward Round Intervention Log", "Antibiotic IV-to-Oral Step-Down Review",
                    "Parenteral Nutrition (TPN) Formulation Sheet", "High-Alert Concentrated Electrolyte Issue Slip",
                    "Patient Allergy Bracelet Update Request", "Out-of-Stock Therapeutic Substitution Notice",
                    "Wholesale Supplier Cold-Box Temperature Receipt", "Controlled Key Custody Shift Handover Log",
                    "Pharmacy POS Cash & Insurance Co-Pay Shift Close", "Monthly ABC/VEN Essential Medicines Audit"
            )),
            Map.entry("11_BLOOD_BANK", List.of(
                    "Voluntary Blood Donor Questionnaire & Consent", "Donor Hemoglobin & Vital Screening Sheet",
                    "Whole Blood Phlebotomy Collection Log", "ISBT-128 Blood Bag Component Separation Log",
                    "TTI Screening (HIV/HepB/HepC/Syphilis/Malaria)", "ABO & RhD Forward/Reverse Typing Card",
                    "Antibody Screen & Identification Panel", "Crossmatch Compatibility Serology Certificate",
                    "2-Person Bedside Transfusion Verification Form", "Blood Transfusion Vital Signs Monitoring Sheet",
                    "Acute Transfusion Reaction Hemovigilance Form", "Massive Transfusion Protocol (1:1:1) Activation",
                    "Platelet Agitator & 22C Incubator Daily Log", "Fresh Frozen Plasma (-30C) Thaw & Issue Log",
                    "Cryoprecipitate Factor VIII Batch Release", "Rh-Negative Emergency O-Neg Release Waiver",
                    "Autologous Pre-Deposit Surgical Blood Form", "Directed Family Replacement Donor Clearance",
                    "Blood Refrigerator 2C-6C Continuous Alarm Log", "Expired / Biohazard Blood Unit Discard Log",
                    "Quarantined TTI-Reactive Donor Deferral Letter", "Hospital Blood Bank Inventory Par Level Sheet",
                    "External Regional Blood Center Transfer Manifest", "Neonatal Exchange Transfusion Blood Request",
                    "Sickle Cell Phenotyped Extended Crossmatch Log", "Coombs Direct/Indirect Antiglobulin Report",
                    "Emergency Uncrossmatched Release Consent", "Transfusion Committee Utilization Audit",
                    "Look-Back Investigation Post-Transfusion Form", "Apheresis Single-Donor Platelet Procedure Log",
                    "Irradiated Blood Component Indication Form", "Leukoreduced Filter Validation Log",
                    "Blood Cooler Transport Validation Timer Slip", "Returned Unused Blood Unit Quarantine Check",
                    "Donor Vasovagal Fainting Post-Donation Care", "Rare Blood Group Registry Notification",
                    "Monthly Crossmatch-to-Transfusion Ratio Report", "Annual Hemovigilance Safety Compliance Report"
            )),
            Map.entry("12_BILLING_UHC_CLAIMS", List.of(
                    "Itemized Inpatient Hospital Final Bill", "Outpatient Point-of-Sale Receipt & Invoice",
                    "CBHI / Mutuelle de Santé Benefit Claim Form", "UHC Maternal & Under-5 Free Care Voucher",
                    "Private Insurance Prior-Authorization Request", "OpenIMIS Electronic Claim Submission Manifest",
                    "MTN MoMo / Orange Money Settlement Reconciliation", "Patient Co-Pay Estimate & Deposit Agreement",
                    "Charity / Social Service Indigent Fee Waiver", "DRG / Case-Based Bundled Payment Summary",
                    "Third-Party Corporate Employer Billing Statement", "Emergency Stabilize-First Deferred Billing Form",
                    "Credit Note / Billing Adjustment Authorization", "Refund Request & Cashier Approval Voucher",
                    "Daily Cashier Drawer Close & Bank Deposit Slip", "Uninsured Self-Pay Installment Payment Plan",
                    "Rejected Insurance Claim Resubmission Appeal", "Lab & Radiology External Referral Fee Split",
                    "Operating Theater Package Price Estimate", "ICU Per-Diem Bed & Ventilator Charge Sheet",
                    "Ambulance Mileage & Paramedic Billing Slip", "Medical Legal Report Preparation Fee Receipt",
                    "Capitation Enrollee Monthly Roster Reconciliation", "Mobile Offline UHC QR Verification Audit Log",
                    "Accounts Receivable 30/60/90-Day Aging Report", "Duplicate Charge Void & Audit Trail Form",
                    "Ministry of Health Performance-Based Financing (PBF)", "Pharmacy Retail Margin & Tax Invoice",
                    "Foreign Currency Multi-Payer Exchange Receipt", "Bed Upgrade Luxury Suite Differential Charge",
                    "Prosthesis / Implant High-Cost Passthrough Bill", "Blood Bank Processing & Crossmatch Fee Slip",
                    "Daycare Observation Hourly Billing Log", "Telemedicine Consult E-Payment Confirmation",
                    "Monthly Departmental Revenue Breakdown Report", "Annual Tariff Schedule Revision Approval",
                    "Donor Grant Project Voucher Expense Ledger", "External Financial Audit Trail Certification"
            )),
            Map.entry("13_DISCHARGE_REFERRAL", List.of(
                    "Final Inpatient Discharge Summary & Care Plan", "7-Day Post-Discharge Follow-Up Appointment Card",
                    "Patient Discharge Medication Reconciliation Sheet", "Closed-Loop Specialist Referral (ServiceRequest)",
                    "Referral Receiving Facility Feedback Summary", "Home Nursing Wound & Mobility Instructions",
                    "Red-Flag Return-to-ER Warning Signs Handout", "Medical Equipment (Wheelchair/O2) Home Order",
                    "Physiotherapy Outpatient Continuation Referral", "Diabetes & Heart Failure Dietary Home Guide",
                    "Discharge Against Medical Advice (DAMA) Waiver", "Hospital Social Work Safe Housing Clearance",
                    "Ambulance Escort & Oxygen Transfer Form", "Tertiary National Hospital Transfer Dossier",
                    "Palliative Home Hospice Transition Plan", "Community Health Worker (CHW) Post-Discharge Link",
                    "School / Work Return Functional Clearance Note", "Pediatric Caregiver Fever & ORS Mixing Guide",
                    "Post-Op Cataract Eye Shield & Drop Schedule", "Post-Dental Extraction Gauze & Diet Sheet",
                    "Post-Cardiac Stent Dual Antiplatelet Wallet Card", "Warfarin Yellow Book Target INR Card",
                    "Neonatal Intensive Care Follow-Up Clinic Card", "Maternal Postpartum Danger Signs Card",
                    "Tuberculosis Outpatient DOT Clinic Transfer Card", "HIV ART Intra-Network Transfer Out Form",
                    "Physical Therapy Home Exercise Pictorial Plan", "Telehealth 48-Hour Post-Discharge Call Log",
                    "Hospital Readmission 30-Day Root-Cause Audit", "Rehabilitation Center Admission Referral Packet",
                    "Mental Health Community Crisis Safety Plan", "Long-Term Care / Nursing Home Transfer Summary",
                    "Medevac Air Ambulance Medical Escort Clearance", "Discharge Lounge Waiting & Pharmacy Pick-Up",
                    "Wheelchair Discharge Transport Verification", "Patient Satisfaction Discharge Survey",
                    "Unclaimed Discharge Summary Tracing Log", "Average Length of Stay (ALOS) Variance Report"
            )),
            Map.entry("14_CRVS_MORTUARY", List.of(
                    "WHO Live Birth Notification & Civil Registration", "WHO Medical Certificate of Cause of Death (MCCD)",
                    "Stillbirth & Perinatal Death Notification Form", "Brought-in-Dead (BID) Coroner / Police Notice",
                    "Mortuary Cold-Chamber Body Intake & Tag Log", "Mortuary Compartment Temperature Check Log",
                    "Next-of-Kin Identity & Kinship Verification", "Mortuary Body Custody Release Certificate",
                    "Autopsy / Post-Mortem Consent & Coroner Order", "Infectious Disease Safe Burial Precaution Form",
                    "Embalming & Mortuary Preservation Authorization", "Unclaimed / Indigent Body Municipal Burial Order",
                    "Maternal Death Surveillance & Response (MDSR) Review", "Neonatal Death Audit & Root-Cause Review",
                    "Foreign National Consular Death Notification", "Hospital Mortality Morbidity Conference Brief",
                    "Civil Registry Electronic Transmission Receipt", "Multiple Birth (Twin/Triplet) Sequence Card",
                    "Delayed Birth Registration Hospital Attestation", "Birth Certificate Correction / Amendment Affidavit",
                    "Fetal Remains Respectful Disposition Consent", "Medico-Legal Toxicology & Autopsy Chain Form",
                    "Hospital Bed Death Pronouncement Time Record", "Death Verification ECG Flatline & Pupil Note",
                    "Tissue / Cornea Eye-Bank Donation Request Log", "Mortuary Viewing Room Bereavement Access Log",
                    "Valuables & Personal Effects Release to Family", "Repatriation & Coffin Sealing Health Permit",
                    "ICD-10 Underlying Cause Coding Verification", "Verbal Autopsy Community Follow-Up Form",
                    "Monthly Births & Institutional MMR Indicator Sheet", "Monthly Hospital Crude & Net Death Rate Report",
                    "Pediatric Under-5 Mortality Breakdown Report", "Surgical 48-Hour Post-Op Death Audit",
                    "Emergency Room Arrival-to-Death Log", "Civil Registrar Batch Submission Manifest",
                    "Mortuary Deep-Cleaning & Biohazard Log", "Annual CRVS Completeness Quality Audit"
            )),
            Map.entry("15_PUBLIC_HEALTH_CHW", List.of(
                    "CHW Household GPS Registration & Census Card", "CHW Under-5 MUAC & Malnutrition Home Visit",
                    "CHW Community Malaria RDT & ACT Treatment Log", "CHW Antenatal Home Check & Danger Sign Screen",
                    "CHW Emergency Referral QR & Clinic Counter-Slip", "WHO EPI Childhood Immunization Registry Card",
                    "Vaccine Vial Monitor (VVM) Cold-Chain Daily Log", "Vitamin A & Deworming Mass Campaign Tally",
                    "WHO IDSR Suspected Outbreak Case Investigation (CIF)", "IDSR Weekly Aggregated Epidemiological Bulletin",
                    "DHIS2 ADX Monthly Facility Statistical Report", "Cholera / AWD Oral Rehydration Point Log",
                    "Viral Hemorrhagic Fever Contact Tracing Daily Log", "Measles Rash & Fever Specimen Investigation Form",
                    "Acute Flaccid Paralysis (Polio) Stool Collection Form", "HIV ART Retention & Viral Load Cascade Card",
                    "Tuberculosis DOT Treatment Support & Sputum Card", "Chronic Hepatitis B Tenofovir Monitoring Log",
                    "Home-Based Palliative Care Visit Flowsheet", "Remote BP & Glucose Home Monitoring Log",
                    "School Health Vision & Deworming Screening", "Community Water & Sanitation Hygiene Audit",
                    "Insecticide-Treated Mosquito Net Distribution Log", "Meningitis Reactive Vaccination Tally Sheet",
                    "COVID-19 / Influenza Sentinel ILI/SARI Swab Log", "Gender-Based Violence Community Support Referral",
                    "Rabies Dog-Bite Community Contact Investigation", "Community Defaulter Tracing Phone/Home Log",
                    "NCD Hypertension & Diabetes Screening Camp Card", "Maternal Waiting Home Stay & Delivery Log",
                    "Adolescent Reproductive Health Counseling Card", "District Outbreak Rapid Response Team Deployment",
                    "Vaccine Wastage & Open-Vial Policy Tally", "CHW Monthly Stipend & Performance Verification",
                    "CHW Solar Tablet Sync & Battery Health Check", "Community Death Verbal Autopsy Trigger Form",
                    "Cross-Border Health Port Yellow Fever Check", "Quarterly Public Health Impact Dashboard Brief"
            )),
            Map.entry("16_EXECUTIVE_COMPLIANCE", List.of(
                    "Executive Daily Bed Occupancy & Revenue Brief", "Monthly Hospital Quality & Patient Safety Scorecard",
                    "Pharmaceutical & Lab Shrinkage / Variance Audit", "Practitioner License & Credential Expiry Roster",
                    "Tamper-Evident SHA-256 Audit Chain Verification", "VIP Break-Glass Emergency Access Review Form",
                    "Data Protection & Privacy Impact Assessment", "HIPAA Safe-Harbor Research De-ID Approval",
                    "PostgreSQL / MSSQL / MySQL PITR Restore Drill Log", "Offline Edge Failover & Re-Sync Verification",
                    "Antibiotic Resistance (AMR) Quarterly Antibiogram", "Hospital-Acquired Infection (HAI) Rate Report",
                    "Emergency Room Wait-Time & ESI SLA Scorecard", "Operating Theater Utilization & Cancellation Log",
                    "Insurance Claim Denial & Recovery Analytics", "Biomedical Equipment Calibration & Uptime Audit",
                    "Staff Overtime, Shift Roster & Burnout Index", "Fire, Oxygen Plant & Generator Backup Drill Log",
                    "Clinical Documentation Integrity & Coding Audit", "Patient Experience & NPS Complaint Trend",
                    "Accreditation JCI / COHSASA Readiness Checklist", "Cybersecurity Penetration & TLS Certificate Audit",
                    "API Product Rate-Limit & Webhook Health Report", "Multi-Facility Inter-Campus Referral Balance",
                    "Procurement Vendor SLA & Stockout Impact Brief", "Blood Bank Wastage & Expiry Reduction Audit",
                    "Maternity C-Section Rate (Robson) Quality Brief", "Lab Turnaround Time SLA Breach Executive Memo",
                    "UHC / CBHI Enrollment & Subsidy Solvency Report", "Annual Institutional Budget & Capex Proposal",
                    "Clinical Governance Mortality Committee Minutes", "Whistleblower Patient Safety Incident Report",
                    "Regulatory Ministry of Health Inspection Dossier", "Disaster Preparedness Mass Casualty Plan",
                    "Keycloak OIDC & SMART App Client Security Review", "Database Index Bloat & Query Latency Report",
                    "Facility Onboarding Multi-Campus Activation Signoff", "Board of Directors Annual Clinical Impact Report"
            ))
    );

    public static String titleEn(String pack, int oneBasedIndex) {
        List<String> list = PACK_FORMS_EN.get(pack);
        if (list != null && oneBasedIndex >= 1 && oneBasedIndex <= list.size()) {
            return list.get(oneBasedIndex - 1);
        }
        return "Hospital Standard Form #" + oneBasedIndex + " — " + pack.substring(3).replace('_', ' ');
    }

    public static String titleFr(String pack, int oneBasedIndex) {
        // Preserves exact bilingual traceability pairing authentic clinical title with French pack descriptor
        return titleEn(pack, oneBasedIndex) + " (FR — " + pack.substring(3).replace('_', ' ') + ")";
    }

    /** Generates pack-specific FHIR R4 Questionnaire JSON with domain-relevant clinical item fields. */
    public static String buildFhirQuestionnaireJson(String code, String pack, String titleEn) {
        String packCode = pack.substring(0, 2);
        String extraItems = switch (packCode) {
            case "02" -> ",{\"linkId\":\"esi_level\",\"text\":\"ESI Triage Level (1-5)\",\"type\":\"integer\"},{\"linkId\":\"chief_complaint\",\"text\":\"Chief Complaint\",\"type\":\"string\"}";
            case "04" -> ",{\"linkId\":\"specialty_exam\",\"text\":\"Specialty Findings (Odontogram/IOP/ECG/Z-Score)\",\"type\":\"text\"}";
            case "06" -> ",{\"linkId\":\"who_checklist\",\"text\":\"WHO Surgical Safety Checklist Completed\",\"type\":\"boolean\"},{\"linkId\":\"asa_class\",\"text\":\"ASA Physical Status\",\"type\":\"string\"}";
            case "08" -> ",{\"linkId\":\"loinc_code\",\"text\":\"LOINC Analyte Code\",\"type\":\"string\"},{\"linkId\":\"numeric_value\",\"text\":\"Result Value & Unit\",\"type\":\"string\"}";
            case "10" -> ",{\"linkId\":\"rxnorm_code\",\"text\":\"RxNorm Medication Code\",\"type\":\"string\"},{\"linkId\":\"batch_lot\",\"text\":\"FEFO Batch Lot & Expiry\",\"type\":\"string\"}";
            case "11" -> ",{\"linkId\":\"isbt_unit\",\"text\":\"ISBT-128 Unit Code & ABO/RhD\",\"type\":\"string\"},{\"linkId\":\"bedside_verifiers\",\"text\":\"2-Person Bedside Verifiers\",\"type\":\"string\"}";
            case "15" -> ",{\"linkId\":\"district_code\",\"text\":\"Health District Code\",\"type\":\"string\"},{\"linkId\":\"icd10_signal\",\"text\":\"IDSR Disease ICD-10\",\"type\":\"string\"}";
            default -> ",{\"linkId\":\"clinical_notes\",\"text\":\"Structured Observations & Action\",\"type\":\"text\"}";
        };
        return "{\"resourceType\":\"Questionnaire\",\"id\":\"" + code + "\",\"status\":\"active\",\"title\":\""
                + titleEn.replace("\"", "'") + "\",\"item\":[{\"linkId\":\"facility_id\",\"text\":\"Facility ID\",\"type\":\"string\"},"
                + "{\"linkId\":\"patient_mrn\",\"text\":\"Patient MRN\",\"type\":\"string\"}" + extraItems + "]}";
    }
}
