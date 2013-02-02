using System;
using System.Collections.Generic;
using System.IO;
using System.Net;


namespace Odyssey_Downloader
{
    class processFile
    {
        protected string fullPath;
        protected string indexFileName;

        public processFile(config settings)
        {
            fullPath = settings.getFullPathToFiles();
            indexFileName = settings.getIndexFileName();
        }

        public bool download(getFileInfo file)
        {
            if (!checkFileList(file.getFullTitle()))
            {
                string filePath = fullPath + file.getNewFileName();
                getFile(file.getFileUrl(), filePath);
                writeToIndex(file.getFullTitle());
                return true;
            }
            else
            {
                Console.WriteLine("Skipping download already have file.");
                return false;
            }
        }

        private void writeToIndex(string title)
        {
            List<string> fileLines = new List<string>();

            int counter = 0;
            string line;
            // Read the file and display it line by line.
            System.IO.StreamReader file = new System.IO.StreamReader(fullPath + indexFileName);
            while ((line = file.ReadLine()) != null)
            {
                fileLines.Add(line);
                counter++;
            }
            file.Close();

            // add new title then sort
            fileLines.Add(title);
            fileLines.Sort();

            // write array back to file
            // create a writer and open the file
            TextWriter newFile = new StreamWriter(fullPath + indexFileName);
            foreach (string Currentline in fileLines)
            {
                newFile.WriteLine(Currentline);
            }
            // close the stream
            newFile.Close();
        }

        private bool checkFileList(string title)
        {
            bool foundInIndex = false;

            int counter = 0;
            string line;

            // Read the file and display it line by line.
            System.IO.StreamReader file = new System.IO.StreamReader(fullPath + indexFileName);
            while ((line = file.ReadLine()) != null)
            {
                if (title == line)
                {
                    foundInIndex = true;
                }
                counter++;
            }
            file.Close();

            return foundInIndex;
        }

        private void getFile(string url, string fileName)
        {
            WebClient Client = new WebClient();
            Client.DownloadFile(url, fileName);
			Client.Dispose();
        }
    }
}
