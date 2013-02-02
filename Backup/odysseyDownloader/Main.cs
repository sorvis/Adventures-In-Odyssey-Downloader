using System;


namespace Odyssey_Downloader
{
    class Program
    {
        static void Main(string[] args)
        {
            // get any arguments passed in at the commandline
            Arguments CommandLine = new Arguments(args);

            // load config file
            config settings = new config();
			
			if (CommandLine["config"] != null)
            {
                settings.setConfigPath(Convert.ToString(CommandLine["config"]));
            }
			else
			{
				settings.setConfigPath("config.xml");
			}
			
			
            // create or update index file
            fileIndex setIndex = new fileIndex(settings);

            // create file downloader
            processFile downloadAndSave = new processFile(settings);


            //***********************************************************
			
			if (CommandLine["rebuild-index"] != null || CommandLine["build-index"] != null)
            {
                setIndex.rebuildIndex();
            }

            if (CommandLine["defaults"] != null)
            {
                settings.setDefaults();
            }

            if (CommandLine["write-defaults"] != null)
            {
                settings.createDefaultConfig();
            }

            if (CommandLine["help"] != null || CommandLine["?"] != null)
            {
                showHelp();
            }
            else if (CommandLine["c"] != null)
            {
                if (CommandLine["c"] == "1")
                {
                    //download just today
                    getFileInfo todaysFile = new getFileInfo(settings, 0);
                    downloadAndSave.download(todaysFile);
                }
                else if (Convert.ToInt16(CommandLine["c"]) > 1)
                {
                    multiDownload download = new multiDownload(settings, ref downloadAndSave, 
                        Convert.ToInt16(CommandLine["c"]));
                }
                else
                {
                    showHelp();
                    Console.WriteLine("\nYou must enter enter a valid number for count 'c'.");
                }
            }
            else if (CommandLine["all"] != null)
            {
                // 35 does whole three weeks
                multiDownload download35 = new multiDownload(settings, ref downloadAndSave, 35);
            }
            else
            {
                // normal mode
                multiDownload downloadNormal = new multiDownload(settings, ref downloadAndSave, 
                    settings.getNormalMode());
            }
        }
        static void showHelp()
        {
            Console.WriteLine(" -?               This Help.");
            Console.WriteLine(" -c               Number of files to download.");
            Console.WriteLine(" --all            Retrieve last three weeks.");
            Console.WriteLine(" --defaults       Run using program defaults.");
            Console.WriteLine(" --write-defaults Over write config file with default settings.");
            Console.WriteLine(" --rebuild-index  Rebuild file index.");
			Console.WriteLine(" --config	  Set path to XML config file.");
        }
    }
}
