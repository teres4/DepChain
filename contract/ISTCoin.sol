// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "./AccessControl.sol";

contract ISTCoin is ERC20 {
    AccessControl private accessControl;

    constructor(address accessControlAddress) ERC20("IST Coin", "IST") {
        accessControl = AccessControl(accessControlAddress);
        _mint(msg.sender, 100000000 * 10 ** decimals());
    }

    function decimals() public pure override returns (uint8) {
        return 2;
    }

    function transfer(address recipient, uint256 amount) public override returns (bool) {
        require(accessControl.isAllowed(msg.sender), "ISTCoin: sender not allowed");
        return super.transfer(recipient, amount);
    }

    function transferFrom(address sender, address recipient, uint256 amount) public override returns (bool) {
        require(accessControl.isAllowed(sender), "ISTCoin: sender not allowed");
        return super.transferFrom(sender, recipient, amount);
    }

    /**
     * @dev Front-running resistant approval.
     * Disallows changing an allowance directly from non-zero to non-zero.
     * Users must first set allowance to 0, then set the new value.
     */
    function approve(address spender, uint256 value) public override returns (bool) {
        uint256 currentAllowance = allowance(_msgSender(), spender);
        require(
            value == 0 || currentAllowance == 0,
            "ISTCoin: reset allowance to 0 first"
        );

        return super.approve(spender, value);
    }

    /**
     * @dev Compare-and-swap style approval update.
     * Succeeds only if current allowance matches `expectedCurrentAllowance`.
     */
    function safeApprove(address spender, uint256 expectedCurrentAllowance, uint256 newAllowance) external returns (bool) {
        address owner = _msgSender();
        require(
            allowance(owner, spender) == expectedCurrentAllowance,
            "ISTCoin: allowance changed"
        );

        _approve(owner, spender, newAllowance);
        return true;
    }
}
