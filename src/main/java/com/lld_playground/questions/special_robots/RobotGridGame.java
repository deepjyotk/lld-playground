package com.lld_playground.questions.special_robots;

import java.util.Optional;
import java.util.Set;

public final class RobotGridGame {

    /* ─────────────────────  BASIC VALUE OBJECTS  ───────────────────── */

    // since we want immutability, we use record. It's simple and easy to use.
    public record Position(int row, int col) {
        public Position translate(Direction d) {
            return translate(d, 1);
        }

        public Position translate(Direction d, int steps) {
            return new Position(row + d.dRow * steps, col + d.dCol * steps);
        }
    }

    public enum Direction {
        N(-1, 0), S(1, 0), E(0, 1), W(0, -1),
        NE(-1, 1), NW(-1, -1), SE(1, 1), SW(1, -1);

        final int dRow, dCol;

        Direction(int r, int c) {
            dRow = r;
            dCol = c;
        }

        static final Direction[] ORTHO = {N, S, E, W};
        static final Direction[] DIAG = {NE, NW, SE, SW};
        static final Direction[] ALL = {N, S, E, W, NE, NW, SE, SW};
    }

    /* ─────────────────────────────  BOARD  ──────────────────────────── */

    public static final class Board {
        private final int rows, cols;
        private final boolean[][] blocked;
        

        // Note: We are using Set to avoid duplicates.
        public Board(int rows, int cols, Set<Position> obstacles) {
            this.rows = rows;
            this.cols = cols;
            blocked = new boolean[rows][cols];
            for (Position p : obstacles) {
                blocked[p.row][p.col] = true;
            }
        }

        public boolean inBounds(Position p) {
            return p.row >= 0 && p.row < rows && p.col >= 0 && p.col < cols;
        }

        public boolean isFree(Position p) {
            return inBounds(p) && !blocked[p.row][p.col];
        }
    }

    /* ─────────────────────────  ROBOT API  ─────────────────────────── */

    public interface Robot {
        Position pos();

        void move(Direction d, Board b);

        boolean attack(Position target);

        boolean specialAttack(Position target);

        void endTurn();

        String type();
    }

    /* ─────────────────────  ABSTRACT BASE CLASS  ───────────────────── */

    public static abstract class BaseRobot implements Robot {

        // A robot has the position, make it protected so that base class can access it.
        protected Position pos;

        protected BaseRobot(Position start) {
            this.pos = start;
        }
        

        //Now, each robot has it's own styles to moves!!!
        protected abstract Direction[] legalMoves();

        // Each
        protected abstract boolean inNormalRange(Position target);

        // Create a getter for outside world to access the position of the robot.
        @Override
        public Position pos() {
            return pos;
        }


        //Now, see the move is common for all the robots, and hence it's implementation lies here, it will have it's implementation.
        // though it uses the legalMoves() method, which is abstract, and hence it will be implemented by the child classes.
        @Override
        public void move(Direction d, Board b) {
            //
            boolean legal = false;

            // now whover is tring to move, i get's it legal moves and compare with the direction it's trying to move.
            for (Direction ld : legalMoves()) {
                if (ld == d) {
                    legal = true;
                    break;
                }
            }

            //this robot made a move that is illegal for it, hence we throw and IllegalArgument Exception.
            if (!legal) throw new IllegalArgumentException(type() + " cannot move " + d);

            // Now, we translate to get the new next pistion
            Position next = pos.translate(d);
            
            if (!b.isFree(next)) throw new IllegalArgumentException("Blocked " + next);
            pos = next;
        }

        @Override
        public boolean attack(Position t) {
            return inNormalRange(t);
        }

        @Override
        public boolean specialAttack(Position t) {
            return false;
        }

        @Override
        public void endTurn() {
        }
    }

    /* ─────────────────────────  ALPHA  ─────────────────────────────── */

    public static final class Alpha extends BaseRobot {
        public Alpha(Position p) {
            super(p);
        }

        @Override
        protected Direction[] legalMoves() {
            return Direction.ORTHO;
        }

        @Override
        protected boolean inNormalRange(Position t) {
            for (Direction d : Direction.ORTHO)
                if (pos.translate(d).equals(t)) return true;
            return false;
        }

        @Override
        public String type() {
            return "ALPHA";
        }
    }

    /* ─────────────────────────  BETA  ──────────────────────────────── */

    public static final class Beta extends BaseRobot {
        private boolean specialUsed = false;

        public Beta(Position p) {
            super(p);
        }

        @Override
        protected Direction[] legalMoves() {
            return Direction.ORTHO;
        }

        @Override
        protected boolean inNormalRange(Position t) {
            for (Direction d : Direction.ORTHO)
                if (pos.translate(d).equals(t)) return true;
            return false;
        }

        @Override
        public boolean specialAttack(Position t) {
            if (specialUsed) return false;
            for (Direction d : Direction.ORTHO) {
                if (pos.translate(d, 2).equals(t)) {
                    specialUsed = true;
                    return true;
                }
            }
            return false;
        }

        @Override
        public String type() {
            return "BETA";
        }
    }

    /* ─────────────────────────  GAMMA  ─────────────────────────────── */

    public static final class Gamma extends BaseRobot {
        public Gamma(Position p) {
            super(p);
        }

        @Override
        protected Direction[] legalMoves() {
            return Direction.ALL;
        }

        @Override
        protected boolean inNormalRange(Position t) {
            for (Direction d : Direction.ALL)
                if (pos.translate(d).equals(t)) return true;
            return false;
        }

        @Override
        public String type() {
            return "GAMMA";
        }
    }

    /* ──────────────────────────  COMMANDS  ─────────────────────────── */

    public interface Command {
    }

    public static final class Move implements Command {
        public final Direction dir;

        public Move(Direction d) {
            dir = d;
        }
    }

    public static final class Attack implements Command {
    }

    public static final class SpecialAttack implements Command {
    }

    /* ─────────────────────────  PLAYER  ─────────────────────────── */

    public static final class Player {
        private final String name;
        private final Robot robot;

        public Player(String name, Robot robot) {
            this.name = name;
            this.robot = robot;
        }

        public String name() {
            return name;
        }

        public Robot robot() {
            return robot;
        }
    }

    /* ─────────────────────────  GAME ENGINE  ───────────────────────── */

    public static final class GameEngine {
        private final Board board;
        private final Player[] players;
        private int turn = 0;
        private String winner = null;

        public GameEngine(Board b, Player p1, Player p2) {
            board = b;
            players = new Player[]{p1, p2};
        }

        public Optional<String> winner() {
            return Optional.ofNullable(winner);
        }

        public boolean play(Command cmd) {
            if (winner != null) throw new IllegalStateException("Game over");

            Player current = players[turn];
            Player enemy = players[turn ^ 1];
            Robot me = current.robot();
            Robot opponent = enemy.robot();

            if (cmd instanceof Move m) {
                me.move(m.dir, board);
            } else if (cmd instanceof Attack) {
                if (me.attack(opponent.pos())) {
                    winner = current.name(); // Winner is player name
                    return true;
                }
            } else if (cmd instanceof SpecialAttack) {
                if (me.specialAttack(opponent.pos())) {
                    winner = current.name(); // Winner is player name
                    return true;
                }
                throw new IllegalArgumentException("Special attack invalid/unavailable");
            } else {
                throw new IllegalArgumentException("Unknown command");
            }

            me.endTurn();
            turn ^= 1; // switch player
            return false;
        }
    }
}


class GameRunner {
    public static void main(String[] args) {
        Set<RobotGridGame.Position> obstacles = Set.of(
                new RobotGridGame.Position(1, 1),
                new RobotGridGame.Position(2, 2)
        );

        RobotGridGame.Board board = new RobotGridGame.Board(10, 10, obstacles);
        RobotGridGame.Robot alpha = new RobotGridGame.Alpha(new RobotGridGame.Position(0, 0));
        RobotGridGame.Robot gamma = new RobotGridGame.Gamma(new RobotGridGame.Position(9, 9));

        RobotGridGame.Player player1 = new RobotGridGame.Player("Alice", alpha);
        RobotGridGame.Player player2 = new RobotGridGame.Player("Bob", gamma);

        RobotGridGame.GameEngine engine = new RobotGridGame.GameEngine(board, player1, player2);

        Scanner sc = new Scanner(System.in);
        System.out.println("Game started!");

        while (engine.winner().isEmpty()) {
            System.out.println("Enter command (move <dir> / attack / special): ");
            String input = sc.nextLine().trim();

            try {
                boolean end = false;
                if (input.startsWith("move")) {
                    String[] parts = input.split(" ");
                    RobotGridGame.Direction d = RobotGridGame.Direction.valueOf(parts[1].toUpperCase());
                    end = engine.play(new RobotGridGame.Move(d));
                } else if (input.equalsIgnoreCase("attack")) {
                    end = engine.play(new RobotGridGame.Attack());
                } else if (input.equalsIgnoreCase("special")) {
                    end = engine.play(new RobotGridGame.SpecialAttack());
                } else {
                    System.out.println("Invalid command.");
                }

                if (end) {
                    System.out.println("Winner: " + engine.winner().orElse("None"));
                    break;
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }

        sc.close();
    }
}


/*
  During the interview, focus on the core logic and the design.


  Starting with Top Bottom approach:
  class GameEngine{
    Note: Since Player's players have to play in alternalte turn, we create turn variable in GameEngine.
    
    private boolean isPlayer1Turn = true;
    private Player[] players;
    public GameEngine(Board b, Player p1 , Player p2){
        this.board = b;
        this.players = new Player[]{p1, p2};
    }

    void play(Command cmd){
        if(winner != null) throw new IllegalStateException("Game over");
        Player current = players[turn];
        Player enemy = players[turn ^ 1];
        Robot me = current.robot();
        Robot opponent = enemy.robot();
    }
  }
 */