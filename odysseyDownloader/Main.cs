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
            Config settings = new Config();

			if (CommandLine["config"] != null)
            {
                settings.SetConfigPath(Convert.ToString(CommandLine["config"]));
            }
			else
			{
				settings.SetConfigPath("config.xml");
			}

			
            // create or update index file
            FileIndex setIndex = new FileIndex(settings);

            // create file downloader
            ProcessFile downloadAndSave = new ProcessFile(settings);


            //***********************************************************

			if (CommandLine["rebuild-index"] != null || CommandLine["build-index"] != null)
            {
                setIndex.RebuildIndex();
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
                    GetFileInfo todaysFile = new GetFileInfo(settings, 0);
                    downloadAndSave.Download(todaysFile);
                }
                else if (Convert.ToInt16(CommandLine["c"]) > 1)
                {
                    MultiDownload download = new MultiDownload(settings, ref downloadAndSave,
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
                MultiDownload download35 = new MultiDownload(settings, ref downloadAndSave, 35);
            }
            else
            {
                // normal mode
                MultiDownload downloadNormal = new MultiDownload(settings, ref downloadAndSave,
                    settings.NormalMode);
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
