// SPDX-License-Identifier: MIT
pragma solidity >=0.8.0 <0.9.0;

contract AccessControl {
    address private owner;
    mapping(address => bool) private allowed;

    constructor() {
        owner = msg.sender;
        allowed[msg.sender] = true;
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "AccessControl: not owner");
        _;
    }

    function allow(address account) external onlyOwner {
        allowed[account] = true;
    }

    function revoke(address account) external onlyOwner {
        allowed[account] = false;
    }

    function isAllowed(address account) external view returns (bool) {
        return allowed[account];
    }
}