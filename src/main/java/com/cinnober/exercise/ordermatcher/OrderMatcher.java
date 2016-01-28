/*
 * Copyright (c) 2014 Cinnober Financial Technology AB, Stockholm,
 * Sweden. All rights reserved.
 * 
 * This software is the confidential and proprietary information of
 * Cinnober Financial Technology AB, Stockholm, Sweden. You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Cinnober.
 * 
 * Cinnober makes no representations or warranties about the suitability
 * of the software, either expressed or implied, including, but not limited
 * to, the implied warranties of merchantibility, fitness for a particular
 * purpose, or non-infringement. Cinnober shall not be liable for any
 * damages suffered by licensee as a result of using, modifying, or
 * distributing this software or its derivatives.
 */

package com.cinnober.exercise.ordermatcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Order book with continuous matching of limit orders with time priority.
 *
 * <p>In an electronic exchange an order book is kept: All
 * buy and sell orders are entered into this order book and the prices are
 * set according to specific rules. Bids and asks are matched and trades
 * occur.

 * <p>This class keeps an order book, that can determine in real-time the
 * current market price and combine matching orders to trades. Each order
 * has a quantity and a price.
 *
 * <p><b>The trading rules:</b>
 * It is a match if a buy order exist at a higher price or equal to a sell
 * order in the order book. The quantity of both orders is reduced as much as
 * possible. When an order has a quantity of zero it is removed. An order can
 * match several other orders if the quantity is large enough and the price is
 * correct. The price of the trade is computed as the order that was in the
 * order book first (the passive party).
 *
 * <p>The priority of the orders to match is based on the following:
 * <ol>
 * <li> On the price that is best for the active order (the one just entered)
 * <li> On the time the order was entered (first come first served)
 * </ol>
 *
 */
public class OrderMatcher {

    LinkedList<Order> orders;

    /**
     * Create a new order matcher.
     */
    public OrderMatcher() {
        orders = new LinkedList<>();
    }
    
    /**
     * Add the specified order to the order book.
     *
     * @param order the order to be added, not null. The order will not be modified by the caller after this call.
     * @return any trades that were created by this order, not null.
     */
    public List<Trade> addOrder(Order order) {
        List<Order> orderList;
        //Add order to order book
        orders.add(order);

        //Collect potential matching orders
        orderList = getFilteredOrdersList(order);

        //Execute trades if it exist potential matching orders.
        return orderList.size() < 1 ? new LinkedList<>() : getAvailableTrades(orderList, order);
    }

    /**
     * Gets all available trades given orders matching and a specified order to match.
     *
     * <p> The given order is matched with the sorted list of orders and then takes the
     * quantity of shares specified in the order until the trades is satisfied and the
     * bids is gone for atleast "one side".
     * @param ords the sorted orders to match the order.
     * @param order the order to match.
     * @return a list of executed trades.
     */
    private List<Trade> getAvailableTrades(List<Order> ords, Order order) {
        LinkedList<Trade> trades = new LinkedList<>();
        long takenQuantity = 0L;

        //Take shares from orders while not enough shares is collected and there
        //still are orders to match against.
        while(takenQuantity < order.getQuantity() && ords.size() > 0) {


            if(takenQuantity + ords.get(0).getQuantity() > order.getQuantity()) {
                //If the currently first order in the list have more shares than needed.
                long takePart = order.getQuantity() - takenQuantity;
                Order firstOrder = ords.get(0);
                firstOrder.takeQuantity(takePart);
                takenQuantity += takePart;

                //Add executed trade
                trades.add(new Trade(order.getId(), firstOrder.getId(), firstOrder.getPrice(), takePart));

            } else if(takenQuantity + ords.get(0).getQuantity() <= order.getQuantity()) {
                //If the currently first order in the list have less or exactly the number
                //of shares needed.

                //Remove order taking from
                Order firstOrder = ords.remove(0);
                removeOrderFromOrders(firstOrder);

                takenQuantity += firstOrder.getQuantity();
                //Add executed trade
                trades.add(new Trade(order.getId(), firstOrder.getId(), firstOrder.getPrice(), firstOrder.getQuantity()));
            }
        }
        //Check if the takenQuantity is less than total needed for order
        if(takenQuantity < order.getQuantity()) {
            //Reduce the needed shares with the amount of collected shares through trading
            order.takeQuantity(takenQuantity);
        } else if(takenQuantity == order.getQuantity()) {
            //If all shares that the order needs is collected, then remove the order.
            removeOrderFromOrders(order);
        }
        //Return all executed trades.
        return trades;
    }

    /**
     * Removes an order from the order book.
     * @param order the order to remove
     */
    private void removeOrderFromOrders(Order order) {
        orders.remove(order);
    }

    /**
     * Returns all orders that could possibly be matched to a given order.
     *
     * <p> The method searches for all orders that have the opposite side
     * as the supplied one. Then depending on either BUY or SELL the price
     * for the orders are then filtered, and only "fitting" orders are chosen
     * as matching from the order book.
     *
     *
     * @param order the order to find matching orders to.
     * @return List of orders from the order book that could be matched to the given order.
     */
    private List<Order> getFilteredOrdersList(Order order) {
        List<Order> matchingOrders;
        if(order.getSide().equals(Side.BUY)) {
            //Match the buy order with sell orders that are lower or equal in price.
            matchingOrders = getOrders(order.getSide().otherSide()).stream().
                    filter(o -> o.getPrice() <= order.getPrice()).
                    collect(Collectors.toList());
        } else {
            //Match the sell order with buy orders that are higher or equal in price.
            matchingOrders = getOrders(order.getSide().otherSide()).stream().
                    filter(o -> o.getPrice() >= order.getPrice()).
                    collect(Collectors.toList());
        }
        return matchingOrders;
    }

    /**
     * Returns all remaining orders in the order book, in priority order, for the specified side.
     *
     * <p>Priority for buy orders is defined as highest price, followed by time priority (first come, first served).
     * For sell orders lowest price comes first, followed by time priority (same as for buy orders).
     *
     * @param side the side, not null.
     * @return all remaining orders in the order book, in priority order, for the specified side, not null.
     */
    public List<Order> getOrders(Side side) {
        //TODO: Fix better storing of orders, unnecessary to filter the whole list every time. But it works... :)
        //Collect all orders for a side.
        LinkedList<Order> matchingOrders = orders.stream().
                filter(o -> o.getSide().equals(side)).
                collect(Collectors.toCollection(LinkedList::new));

        //Sort the collected list
        Collections.sort(matchingOrders);
        return matchingOrders;
    }



    public static void main(String... args) throws Exception {
        OrderMatcher matcher = new OrderMatcher();
        System.out.println("Welcome to the order matcher. Type 'help' for a list of commands.");
        System.out.println();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        LOOP: while ((line=reader.readLine()) != null) {
            line = line.trim();
            try {
                switch(line) {
                    case "help":
                        System.out.println("Available commands: \n"
                                + "  buy|sell <quantity>@<price> [#<id>]  - Enter an order.\n"
                                + "  list                                 - List all remaining orders.\n"
                                + "  quit                                 - Quit.\n"
                                + "  help                                 - Show help (this message).\n");
                        break;
                    case "":
                        // ignore
                        break;
                    case "quit":
                        break LOOP;
                    case "list":
                        System.out.println("BUY:");
                        matcher.getOrders(Side.BUY).stream().map(Order::toString).forEach(System.out::println);
                        System.out.println("SELL:");
                        matcher.getOrders(Side.SELL).stream().map(Order::toString).forEach(System.out::println);
                        break;
                    default: // order
                        matcher.addOrder(Order.parse(line)).stream().map(Trade::toString).forEach(System.out::println);
                        break;
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Bad input: " + e.getMessage());
            } catch (UnsupportedOperationException e) {
                System.err.println("Sorry, this command is not supported yet: " + e.getMessage());
            }
        }
        System.out.println("Good bye!");
    }
}
