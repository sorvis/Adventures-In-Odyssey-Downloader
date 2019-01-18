using System;
using System.Xml;

namespace Odyssey_Downloader
{
    public class Config
    {
        protected string pageUrl;
        protected string fileExtension;
        protected string titleStart;
        protected string titleEnd;
        protected string dateFormat;
        protected string fullPathToFiles;
        protected string indexFileName;
        protected int normalMode;
        protected string configPATH = "config.xml";

        public void SetConfigPath(string path)
        {
            configPATH = path;

            if (checkConfigFile())
            {
                loadConfigFile();
            }
            else
            {
                setDefaults();
                createDefaultConfig();
            }
        }

        public string Url => pageUrl;

        public string FileExtension => fileExtension;

        public string TitleStart => titleStart;

        public string TitleEnd => titleEnd;

        public string DateFormat => dateFormat;

        public string FullPathToFiles => fullPathToFiles;

        public string IndexFileName => indexFileName;

        public int NormalMode => normalMode;

        public Config()
        {
            /*   if (checkConfigFile())
               {
                   loadConfigFile();
               }
               else
               {
                   setDefaults();
                   createDefaultConfig();
               }
               */
        }

        private void loadConfigFile()
        {
            XmlTextReader reader = new XmlTextReader(configPATH);
            string element = "";
            while (reader.Read())
            {
                switch (reader.NodeType)
                {
                    case XmlNodeType.Element: // The node is an element.
                        element = reader.Name;
                        break;

                    case XmlNodeType.Text: //Display the text in each element.
                        setConfig(element, reader.Value);
                        break;
                }
            }
        }

        private void setConfig(string element, string data)
        {
            switch (element)
            {
                case "sourceURL":
                    pageUrl = data;
                    break;

                case "dateFormat":
                    dateFormat = data;
                    break;

                case "fileExtension":
                    fileExtension = data;
                    break;

                case "titleStart":
                    titleStart = data;
                    break;

                case "titleEnd":
                    titleEnd = data;
                    break;

                case "fullPathToFiles":
                    fullPathToFiles = data;
                    break;

                case "indexFileName":
                    indexFileName = data;
                    break;

                case "normalMode":
                    normalMode = Convert.ToInt16(data);
                    break;
            }
        }

        private bool checkConfigFile()
        {
            bool foundConfig;

            try
            {
                System.IO.StreamReader file = new System.IO.StreamReader(configPATH);
                file.Close();
                foundConfig = true;
            }
            catch
            {
                Console.WriteLine("Found no config file to setting defaults.");
                foundConfig = false;
            }

            return foundConfig;
        }

        public void createDefaultConfig()
        {
            //create default file
            XmlTextWriter textWriter = new XmlTextWriter(configPATH, null);

            textWriter.WriteStartDocument();
            textWriter.WriteStartElement("root"); //must have!!!!

            // Write next element
            textWriter.WriteStartElement("sourceURL");
            textWriter.WriteString(pageUrl);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("dateFormat");
            textWriter.WriteString(dateFormat);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("fileExtension");
            textWriter.WriteString(fileExtension);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("titleStart");
            textWriter.WriteString(titleStart);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("titleEnd");
            textWriter.WriteString(titleEnd);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("fullPathToFiles");
            textWriter.WriteString(fullPathToFiles);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("indexFileName");
            textWriter.WriteString(indexFileName);
            textWriter.WriteEndElement();

            // Write next element
            textWriter.WriteStartElement("normalMode", "Number of files to download normally");
            textWriter.WriteString(Convert.ToString(normalMode));
            textWriter.WriteEndElement();

            // root element end
            textWriter.WriteEndElement();

            // Ends the document.
            textWriter.WriteEndDocument();
            // close writer
            textWriter.Close();
        }

        public void setDefaults()
        {
            pageUrl = "https://www.oneplace.com/ministries/adventures-in-odyssey/listen/";
            dateFormat = "{0:MMMM d, yyyy}";
            fileExtension = ".mp3";
            titleStart = "<div class=\"title\" title=\"\">";
            titleEnd = "<label class=\"duration\">";
            fullPathToFiles = "";
            indexFileName = "index.txt";
            normalMode = 3;
        }
    }
}